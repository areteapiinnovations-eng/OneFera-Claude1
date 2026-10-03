/**
 * OneFera Cloud Functions.
 *
 * Clients never write counters, Aura or other people's notifications (see firestore.rules).
 * These triggers keep them consistent instead:
 *  - likes / comments      -> post counters, author Aura, notifications
 *  - posts                 -> author postsCount + Aura, hashtag counts, media cleanup
 *  - follows / requests    -> follower counters, notifications
 *  - chat messages         -> conversation preview, unread counts, push
 *  - hourly                -> expired stories removed
 *  - shop (callable)       -> checkout, payment confirmation, cancellation; simulated courier
 */
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { setGlobalOptions } from "firebase-functions/v2";
import { onDocumentCreated, onDocumentDeleted } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { createHmac, timingSafeEqual } from "node:crypto";
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
  const open = post?.id ? `post:${post.id}` : type === "FollowRequest" ? "notifications" : `user:${actorUid}`;
  await sendPush(recipientUid, "OneFera", `${actor.displayName} ${text}`, { open, type });
}

/**
 * Sends a push to every registered device of the user (tokens in users/{uid}/fcmTokens/{token}).
 * `data.open` tells the app which screen to open when the notification is tapped.
 */
async function sendPush(uid: string, title: string, body: string, data: Record<string, string>): Promise<void> {
  const tokens = await db.collection("users").doc(uid).collection("fcmTokens").get();
  if (tokens.empty) return;
  const response = await getMessaging().sendEachForMulticast({
    tokens: tokens.docs.map((d) => d.id),
    notification: { title, body },
    data: { ...data, title, body },
    android: { priority: "high", notification: { channelId: "onefera_general", tag: data.conversationId ?? data.open } },
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

// ---------------------------------------------------------------- chat

export const onMessageCreated = onDocumentCreated("conversations/{cid}/messages/{mid}", async (event) => {
  const { cid } = event.params;
  const message = event.data?.data();
  if (!message) return;
  const senderId = message.senderId as string;
  const convoRef = db.collection("conversations").doc(cid);
  const convo = await convoRef.get();
  if (!convo.exists) return;
  const memberIds = (convo.get("memberIds") as string[]) ?? [];
  const recipients = memberIds.filter((m) => m !== senderId);
  const attachment = message.attachment as { type?: string; name?: string } | undefined;
  const preview = (message.text as string | undefined)?.trim()
    || (attachment?.type === "Image" ? "📷 Photo" : attachment ? `📎 ${attachment.name || "File"}` : "");

  const update: Record<string, unknown> = {
    lastMessage: preview.slice(0, 120),
    lastSenderId: senderId,
    lastMessageAt: Date.now(),
    [`typing.${senderId}`]: 0,
  };
  for (const r of recipients) update[`unreadCounts.${r}`] = FieldValue.increment(1);
  await convoRef.update(update);

  const sender = await userSummary(senderId);
  await Promise.all(
    recipients.map((r) =>
      sendPush(r, sender?.displayName ?? "New message", preview.slice(0, 140), { open: `chat:${cid}`, type: "Chat", conversationId: cid }),
    ),
  );
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

// ---------------------------------------------------------------- shop

/**
 * Payments run in "simulated" mode until live Razorpay keys are configured in
 * functions/.env (PAYMENTS_MODE=live, RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET). Prices, stock
 * and payment verification are always decided here, never on the device.
 */
const FREE_DELIVERY_ABOVE = 499;
const DELIVERY_FEE = 49;
const MAX_QUANTITY = 10;
const PAYMENT_METHODS = ["Upi", "Card", "NetBanking", "Cod"];
const ACTIVE_STEPS = ["Placed", "Packed", "Shipped", "OutForDelivery"] as const;
const DAY = 24 * 60 * 60 * 1000;

function paymentsLive(): boolean {
  return process.env.PAYMENTS_MODE === "live" && !!process.env.RAZORPAY_KEY_ID && !!process.env.RAZORPAY_KEY_SECRET;
}

function requireUid(auth: { uid: string } | undefined): string {
  if (!auth) throw new HttpsError("unauthenticated", "Please log in again.");
  return auth.uid;
}

interface Address { name: string; phone: string; line1: string; line2: string; city: string; state: string; pincode: string }

function validAddress(a: unknown): Address {
  const x = (a ?? {}) as Record<string, unknown>;
  const str = (k: string, max: number) => (typeof x[k] === "string" ? (x[k] as string).trim().slice(0, max) : "");
  const address: Address = {
    name: str("name", 60),
    phone: str("phone", 10),
    line1: str("line1", 120),
    line2: str("line2", 120),
    city: str("city", 40),
    state: str("state", 40),
    pincode: str("pincode", 6),
  };
  if (!address.name || !/^[0-9]{10}$/.test(address.phone) || !address.line1 || !address.city || !address.state || !/^[0-9]{6}$/.test(address.pincode)) {
    throw new HttpsError("invalid-argument", "Please complete your delivery address.");
  }
  return address;
}

interface OrderLine {
  product: { id: string; title: string; brand: string; imageUrl: string | null; price: number; mrp: number; sellerId: string };
  variant: string;
  quantity: number;
  price: number;
}

export const startCheckout = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const address = validAddress(request.data?.address);
  const method = String(request.data?.method ?? "");
  if (!PAYMENT_METHODS.includes(method)) throw new HttpsError("invalid-argument", "Choose a payment method.");

  const cart = await db.collection("users").doc(uid).collection("cart").get();
  if (cart.empty) throw new HttpsError("failed-precondition", "Your cart is empty.");

  // Re-price every line from the catalogue.
  const lines: OrderLine[] = [];
  for (const line of cart.docs) {
    const productId = line.get("product.id") as string;
    const quantity = Math.max(1, Math.min(MAX_QUANTITY, Number(line.get("quantity")) || 1));
    const variant = String(line.get("variant") ?? "");
    const product = await db.collection("products").doc(productId).get();
    if (!product.exists) throw new HttpsError("failed-precondition", `${line.get("product.title") ?? "An item"} is no longer available.`);
    const p = product.data()!;
    const variants = (p.variants as string[] | undefined) ?? [];
    if (variants.length > 0 && !variants.includes(variant)) throw new HttpsError("failed-precondition", `Pick an option for ${p.title}.`);
    if ((p.stock ?? 0) < quantity) throw new HttpsError("failed-precondition", `Only ${p.stock ?? 0} left of ${p.title}.`);
    lines.push({
      product: {
        id: product.id,
        title: p.title ?? "",
        brand: p.brand ?? "",
        imageUrl: (p.images as string[] | undefined)?.[0] ?? null,
        price: p.price ?? 0,
        mrp: p.mrp ?? 0,
        sellerId: p.sellerId ?? "",
      },
      variant,
      quantity,
      price: p.price ?? 0,
    });
  }
  const subtotal = lines.reduce((sum, l) => sum + l.price * l.quantity, 0);
  const deliveryFee = subtotal >= FREE_DELIVERY_ABOVE ? 0 : DELIVERY_FEE;
  const total = subtotal + deliveryFee;

  const ref = db.collection("orders").doc();
  const live = paymentsLive() && method !== "Cod";
  let razorpayOrderId = "";
  if (live) {
    const auth = Buffer.from(`${process.env.RAZORPAY_KEY_ID}:${process.env.RAZORPAY_KEY_SECRET}`).toString("base64");
    const res = await fetch("https://api.razorpay.com/v1/orders", {
      method: "POST",
      headers: { "Authorization": `Basic ${auth}`, "Content-Type": "application/json" },
      body: JSON.stringify({ amount: total * 100, currency: "INR", receipt: ref.id }),
    });
    if (!res.ok) {
      logger.error("Razorpay order failed", res.status, await res.text());
      throw new HttpsError("unavailable", "Payments are busy right now. Please try again.");
    }
    razorpayOrderId = ((await res.json()) as { id: string }).id;
  }

  await ref.set({
    buyerId: uid,
    items: lines,
    sellerIds: [...new Set(lines.map((l) => l.product.sellerId).filter((s) => s))],
    subtotal,
    deliveryFee,
    total,
    address,
    paymentMethod: method,
    paymentId: "",
    razorpayOrderId,
    status: "PendingPayment",
    history: {},
    createdAt: FieldValue.serverTimestamp(),
  });
  await db.collection("users").doc(uid).collection("addresses").doc("default").set(address);
  return { orderId: ref.id, amount: total, razorpayOrderId, keyId: live ? process.env.RAZORPAY_KEY_ID : "", simulated: !live };
});

function verifyRazorpaySignature(orderId: string, paymentId: string, signature: string): boolean {
  const expected = createHmac("sha256", process.env.RAZORPAY_KEY_SECRET ?? "").update(`${orderId}|${paymentId}`).digest("hex");
  const a = Buffer.from(expected);
  const b = Buffer.from(String(signature));
  return a.length === b.length && timingSafeEqual(a, b);
}

export const confirmPayment = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const orderId = String(request.data?.orderId ?? "");
  const paymentId = String(request.data?.paymentId ?? "").slice(0, 80);
  const signature = String(request.data?.signature ?? "");
  if (!orderId || !paymentId) throw new HttpsError("invalid-argument", "Missing payment details.");
  const ref = db.collection("orders").doc(orderId);

  const order = await ref.get();
  if (!order.exists || order.get("buyerId") !== uid) throw new HttpsError("not-found", "We couldn't find that order.");
  if (order.get("status") !== "PendingPayment") return { status: order.get("status") };

  const method = order.get("paymentMethod") as string;
  const razorpayOrderId = order.get("razorpayOrderId") as string;
  if (method !== "Cod") {
    if (razorpayOrderId) {
      if (!verifyRazorpaySignature(razorpayOrderId, paymentId, signature)) {
        throw new HttpsError("permission-denied", "We couldn't verify this payment. If money was taken it will be refunded.");
      }
    } else if (paymentsLive() || !paymentId.startsWith("pay_sim_")) {
      throw new HttpsError("permission-denied", "We couldn't verify this payment.");
    }
  }

  const lines = (order.get("items") as OrderLine[]) ?? [];
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const fresh = await tx.get(ref);
    if (fresh.get("status") !== "PendingPayment") return;
    const products = await Promise.all(lines.map((l) => tx.get(db.collection("products").doc(l.product.id))));
    products.forEach((p, i) => {
      if (!p.exists || (p.get("stock") ?? 0) < lines[i].quantity) {
        throw new HttpsError("failed-precondition", `Sorry, ${lines[i].product.title} just sold out. Any payment will be refunded.`);
      }
    });
    products.forEach((p, i) => {
      tx.update(p.ref, { stock: FieldValue.increment(-lines[i].quantity), soldCount: FieldValue.increment(lines[i].quantity) });
    });
    tx.update(ref, {
      status: "Placed",
      paymentId,
      "history.Placed": Timestamp.fromMillis(now),
      estimatedDelivery: now + 3 * DAY,
    });
  });

  // Empty the cart (only the lines that were bought).
  const cart = await db.collection("users").doc(uid).collection("cart").get();
  const bought = new Set(lines.map((l) => l.product.id));
  await Promise.all(cart.docs.filter((d) => bought.has(d.get("product.id"))).map((d) => d.ref.delete()));

  await sendPush(uid, "Order placed 🎉", `We've got your order of ₹${order.get("total")}. We'll keep you posted!`, { open: `order:${orderId}`, type: "Order" });
  // Tell each seller in the order about their new sale.
  const sellerIds = (order.get("sellerIds") as string[] | undefined) ?? [];
  await Promise.all(
    sellerIds.map((sellerId) => {
      const mine = lines.filter((l) => l.product.sellerId === sellerId);
      const units = mine.reduce((n, l) => n + l.quantity, 0);
      const amount = mine.reduce((n, l) => n + l.price * l.quantity, 0);
      return sendPush(sellerId, "New order 🛍️", `${units} × ${mine[0]?.product.title ?? "item"} · ₹${amount}`, { open: `sellerorder:${orderId}`, type: "SellerOrder" });
    }),
  );
  return { status: "Placed" };
});

export const cancelOrder = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const orderId = String(request.data?.orderId ?? "");
  const ref = db.collection("orders").doc(orderId);
  await db.runTransaction(async (tx) => {
    const order = await tx.get(ref);
    if (!order.exists || order.get("buyerId") !== uid) throw new HttpsError("not-found", "Order not found.");
    if (!["Placed", "Packed"].includes(order.get("status"))) {
      throw new HttpsError("failed-precondition", "This order has already shipped, so it can't be cancelled.");
    }
    const lines = (order.get("items") as OrderLine[]) ?? [];
    for (const l of lines) {
      tx.update(db.collection("products").doc(l.product.id), {
        stock: FieldValue.increment(l.quantity),
        soldCount: FieldValue.increment(-l.quantity),
      });
    }
    tx.update(ref, { "status": "Cancelled", "history.Cancelled": Timestamp.now() });
  });
  return { status: "Cancelled" };
});

/**
 * Simulated courier: while payments are simulated, paid orders move through the delivery
 * steps on their own so tracking can be tried end to end. Unpaid checkouts expire after a day.
 * With live payments, sellers will advance orders from Seller mode instead.
 */
export const advanceSimulatedOrders = onSchedule("every 15 minutes", async () => {
  const now = Date.now();
  const expired = await db.collection("orders").where("status", "==", "PendingPayment")
    .where("createdAt", "<=", Timestamp.fromMillis(now - DAY)).limit(200).get();
  await Promise.all(expired.docs.map((d) => d.ref.delete()));
  if (paymentsLive()) return;

  // Minimum age (since placed) before each next step.
  const after: Record<string, { next: string; age: number; title: string }> = {
    Placed: { next: "Packed", age: 15 * 60 * 1000, title: "Packed and ready 📦" },
    Packed: { next: "Shipped", age: 2 * 60 * 60 * 1000, title: "Your order has shipped 🚚" },
    Shipped: { next: "OutForDelivery", age: 24 * 60 * 60 * 1000, title: "Out for delivery today 🛵" },
    OutForDelivery: { next: "Delivered", age: 30 * 60 * 60 * 1000, title: "Delivered! Enjoy ✨" },
  };
  for (const status of ACTIVE_STEPS) {
    const step = after[status];
    const due = await db.collection("orders").where("status", "==", status)
      .where("createdAt", "<=", Timestamp.fromMillis(now - step.age)).limit(200).get();
    await Promise.all(
      // Orders with marketplace sellers are fulfilled by the sellers themselves (Seller hub).
      due.docs.filter((d) => ((d.get("sellerIds") as string[] | undefined) ?? []).length === 0).map(async (d) => {
        await d.ref.update({ "status": step.next, [`history.${step.next}`]: Timestamp.now() });
        await sendPush(d.get("buyerId"), step.title, `Order ${d.id}`, { open: `order:${d.id}`, type: "Order" });
      }),
    );
  }
});

const NEXT_STATUS: Record<string, { next: string; title: string }> = {
  Placed: { next: "Packed", title: "Packed and ready 📦" },
  Packed: { next: "Shipped", title: "Your order has shipped 🚚" },
  Shipped: { next: "OutForDelivery", title: "Out for delivery today 🛵" },
  OutForDelivery: { next: "Delivered", title: "Delivered! Enjoy ✨" },
};

/** Seller hub: a seller in the order moves it one step along the delivery timeline. */
export const updateOrderStatus = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const orderId = String(request.data?.orderId ?? "");
  const ref = db.collection("orders").doc(orderId);
  const result = await db.runTransaction(async (tx) => {
    const order = await tx.get(ref);
    const sellerIds = (order.get("sellerIds") as string[] | undefined) ?? [];
    if (!order.exists || !sellerIds.includes(uid)) throw new HttpsError("not-found", "Order not found.");
    const step = NEXT_STATUS[order.get("status") as string];
    if (!step) throw new HttpsError("failed-precondition", `This order is already ${String(order.get("status")).toLowerCase()}.`);
    tx.update(ref, { "status": step.next, [`history.${step.next}`]: Timestamp.now() });
    return { buyerId: order.get("buyerId") as string, ...step };
  });
  await sendPush(result.buyerId, result.title, `Order ${orderId}`, { open: `order:${orderId}`, type: "Order" });
  return { status: result.next };
});
