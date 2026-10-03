/**
 * OneFera Cloud Functions.
 *
 * Clients never write counters, Aura or other people's notifications (see firestore.rules).
 * These triggers keep them consistent instead:
 *  - likes / comments      -> post counters, author Aura, notifications
 *  - posts                 -> author postsCount + Aura, hashtag counts, media cleanup
 *  - follows / requests    -> follower counters, notifications
 *  - hourly                -> expired stories removed
 */
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { setGlobalOptions } from "firebase-functions/v2";
import { onDocumentCreated, onDocumentDeleted } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";

initializeApp();
setGlobalOptions({ region: "asia-south1", maxInstances: 20 });

const db = getFirestore();
const AURA_MAX = 1000;

const AURA = {
  post: 10,
  likeReceived: 2,
  commentReceived: 3,
  newFollower: 1,
};

type NotificationType = "Like" | "Comment" | "Follow" | "FollowRequest" | "FollowAccepted";

interface UserSummary {
  uid: string;
  displayName: string;
  username: string;
  avatarUrl: string | null;
  verified: boolean;
  isPrivate: boolean;
}

async function userSummary(uid: string): Promise<UserSummary | null> {
  const doc = await db.collection("users").doc(uid).get();
  if (!doc.exists) return null;
  const d = doc.data()!;
  return {
    uid,
    displayName: d.displayName ?? "",
    username: d.username ?? "",
    avatarUrl: d.avatarUrl ?? null,
    verified: d.verified ?? false,
    isPrivate: d.isPrivate ?? false,
  };
}

/** Adds Aura points, clamped to 0..1000. */
async function addAura(uid: string, points: number): Promise<void> {
  const ref = db.collection("users").doc(uid);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    const current = (snap.get("auraPoints") as number | undefined) ?? 0;
    tx.update(ref, { auraPoints: Math.max(0, Math.min(AURA_MAX, current + points)) });
  });
}

function bump(uid: string, field: string, delta: number) {
  return db.collection("users").doc(uid).update({ [field]: FieldValue.increment(delta) }).catch((e) => {
    logger.warn(`Could not update ${field} for ${uid}`, e);
  });
}

async function notify(
  recipientUid: string,
  type: NotificationType,
  actorUid: string,
  text: string,
  post?: { id: string; thumb: string | null },
): Promise<void> {
  if (recipientUid === actorUid) return;
  const actor = await userSummary(actorUid);
  if (!actor) return;
  await db.collection("users").doc(recipientUid).collection("notifications").add({
    type,
    actor,
    text,
    postId: post?.id ?? null,
    postThumbUrl: post?.thumb ?? null,
    read: false,
    createdAt: FieldValue.serverTimestamp(),
  });
  await sendPush(recipientUid, `${actor.displayName} ${text}`);
}

/** Sends a push to every registered device of the user (tokens in users/{uid}/fcmTokens/{token}). */
async function sendPush(uid: string, body: string): Promise<void> {
  const tokens = await db.collection("users").doc(uid).collection("fcmTokens").get();
  if (tokens.empty) return;
  const response = await getMessaging().sendEachForMulticast({
    tokens: tokens.docs.map((d) => d.id),
    notification: { title: "OneFera", body },
    android: { notification: { channelId: "onefera_general" } },
  });
  // Drop tokens that are no longer valid.
  await Promise.all(
    response.responses.map((r, i) =>
      !r.success && r.error?.code === "messaging/registration-token-not-registered" ? tokens.docs[i].ref.delete() : null,
    ),
  );
}

async function postInfo(postId: string): Promise<{ authorId: string; thumb: string | null } | null> {
  const doc = await db.collection("posts").doc(postId).get();
  if (!doc.exists) return null;
  const media = (doc.get("media") as Array<{ url: string; type: string; thumbnailUrl?: string }> | undefined) ?? [];
  const first = media[0];
  const thumb = first ? first.thumbnailUrl ?? (first.type === "Image" ? first.url : null) : null;
  return { authorId: doc.get("authorId"), thumb };
}

// ---------------------------------------------------------------- likes

export const onLikeCreated = onDocumentCreated("posts/{postId}/likes/{uid}", async (event) => {
  const { postId, uid } = event.params;
  await db.collection("posts").doc(postId).update({ likeCount: FieldValue.increment(1) });
  const post = await postInfo(postId);
  if (!post || post.authorId === uid) return;
  await addAura(post.authorId, AURA.likeReceived);
  await notify(post.authorId, "Like", uid, "liked your post", { id: postId, thumb: post.thumb });
});

export const onLikeDeleted = onDocumentDeleted("posts/{postId}/likes/{uid}", async (event) => {
  await db.collection("posts").doc(event.params.postId).update({ likeCount: FieldValue.increment(-1) }).catch(() => undefined);
});

// ---------------------------------------------------------------- comments

export const onCommentCreated = onDocumentCreated("posts/{postId}/comments/{commentId}", async (event) => {
  const { postId } = event.params;
  const authorId = event.data?.get("authorId") as string | undefined;
  const text = ((event.data?.get("text") as string | undefined) ?? "").slice(0, 80);
  await db.collection("posts").doc(postId).update({ commentCount: FieldValue.increment(1) });
  const post = await postInfo(postId);
  if (!post || !authorId || post.authorId === authorId) return;
  await addAura(post.authorId, AURA.commentReceived);
  await notify(post.authorId, "Comment", authorId, `commented: ${text}`, { id: postId, thumb: post.thumb });
});

export const onCommentDeleted = onDocumentDeleted("posts/{postId}/comments/{commentId}", async (event) => {
  await db.collection("posts").doc(event.params.postId).update({ commentCount: FieldValue.increment(-1) }).catch(() => undefined);
});

// ---------------------------------------------------------------- posts

export const onPostCreated = onDocumentCreated("posts/{postId}", async (event) => {
  const authorId = event.data?.get("authorId") as string | undefined;
  if (!authorId) return;
  await bump(authorId, "postsCount", 1);
  await addAura(authorId, AURA.post);
  const tags = (event.data?.get("tags") as string[] | undefined) ?? [];
  const visibility = event.data?.get("visibility");
  if (visibility === "public") {
    await Promise.all(tags.map((tag) => db.collection("tags").doc(tag).set({ postCount: FieldValue.increment(1) }, { merge: true })));
  }
});

export const onPostDeleted = onDocumentDeleted("posts/{postId}", async (event) => {
  const { postId } = event.params;
  const authorId = event.data?.get("authorId") as string | undefined;
  if (!authorId) return;
  await bump(authorId, "postsCount", -1);
  const tags = (event.data?.get("tags") as string[] | undefined) ?? [];
  if (event.data?.get("visibility") === "public") {
    await Promise.all(tags.map((tag) => db.collection("tags").doc(tag).set({ postCount: FieldValue.increment(-1) }, { merge: true })));
  }
  await db.recursiveDelete(db.collection("posts").doc(postId));
  await getStorage().bucket().deleteFiles({ prefix: `posts/${authorId}/${postId}/` }).catch((e) => logger.warn("media cleanup", e));
});

// ---------------------------------------------------------------- follows

export const onFollowerCreated = onDocumentCreated("users/{uid}/followers/{followerId}", async (event) => {
  const { uid, followerId } = event.params;
  await bump(uid, "followersCount", 1);
  await bump(followerId, "followingCount", 1);
  await addAura(uid, AURA.newFollower);
  if (event.data?.get("viaRequest") === true) {
    // The account owner approved a request: tell the requester.
    await notify(followerId, "FollowAccepted", uid, "accepted your follow request");
  } else {
    await notify(uid, "Follow", followerId, "started following you");
  }
});

export const onFollowerDeleted = onDocumentDeleted("users/{uid}/followers/{followerId}", async (event) => {
  const { uid, followerId } = event.params;
  await bump(uid, "followersCount", -1);
  await bump(followerId, "followingCount", -1);
});

export const onFollowRequestCreated = onDocumentCreated("users/{uid}/followRequests/{requesterId}", async (event) => {
  const { uid, requesterId } = event.params;
  await notify(uid, "FollowRequest", requesterId, "requested to follow you");
});

// ---------------------------------------------------------------- stories

export const cleanUpExpiredStories = onSchedule("every 60 minutes", async () => {
  const expired = await db.collection("stories").where("expiresAt", "<=", Timestamp.now().toMillis()).limit(400).get();
  if (expired.empty) return;
  const bucket = getStorage().bucket();
  await Promise.all(
    expired.docs.map(async (doc) => {
      const authorId = doc.get("authorId");
      await bucket.file(`stories/${authorId}/${doc.id}.jpg`).delete().catch(() => undefined);
      await doc.ref.delete();
    }),
  );
  logger.info(`Removed ${expired.size} expired stories`);
});
