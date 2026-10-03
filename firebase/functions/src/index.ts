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
 *  - rewards (callable)    -> daily check-in streaks, Mystery Boxes, coupons, memberships
 */
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { setGlobalOptions } from "firebase-functions/v2";
import { onDocumentCreated, onDocumentDeleted, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { GoogleAuth } from "google-auth-library";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
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
  const buyer = await db.collection("users").doc(uid).get();
  const plus = hasPlusPerks(buyer.get("membershipPlan"), buyer.get("membershipExpiresAt"));
  const couponId = typeof request.data?.couponId === "string" ? (request.data.couponId as string) : "";
  let discount = 0;
  let waived = plus;
  if (couponId) {
    const coupon = await db.collection("users").doc(uid).collection("coupons").doc(couponId).get();
    if (!coupon.exists || coupon.get("used") || (coupon.get("expiresAt") ?? 0) <= Date.now()) {
      throw new HttpsError("failed-precondition", "That coupon has expired or was already used.");
    }
    const c = coupon.data() as Coupon;
    if (subtotal < c.minOrder) throw new HttpsError("failed-precondition", `Add ₹${c.minOrder - subtotal} more to use this coupon.`);
    discount = couponDiscount(c, subtotal);
    if (c.kind === "FreeDelivery") waived = true;
  }
  const deliveryFee = subtotal >= FREE_DELIVERY_ABOVE || waived ? 0 : DELIVERY_FEE;
  const total = Math.max(0, subtotal + deliveryFee - discount);

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
    discount,
    couponId,
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
    const couponId = fresh.get("couponId") as string | undefined;
    if (couponId) tx.update(db.collection("users").doc(uid).collection("coupons").doc(couponId), { used: true });
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

// ---------------------------------------------------------------- rewards

/**
 * The Aura economy. Mirrors `Rewards` in the app (data/model/Rewards.kt) so demo mode and the
 * live backend behave the same.
 */
const CHECK_IN_AURA = 5;
const WEEK_BONUS_AURA = 25;
const COUPON_DAYS = 14;
const MEMBERSHIP_DAYS = 30;
const PLANS = ["Plus", "SellerPro"];

interface Coupon { kind: "Flat" | "Percent" | "FreeDelivery"; value: number; minOrder: number; maxDiscount: number; expiresAt: number; used: boolean }

function hasPlusPerks(plan: unknown, expiresAt: unknown): boolean {
  return PLANS.includes(String(plan)) && Number(expiresAt ?? 0) > Date.now();
}

function couponDiscount(c: Coupon, subtotal: number): number {
  if (subtotal < c.minOrder) return 0;
  if (c.kind === "Flat") return Math.min(c.value, subtotal);
  if (c.kind === "Percent") {
    const d = Math.floor((subtotal * c.value) / 100);
    return c.maxDiscount > 0 ? Math.min(d, c.maxDiscount) : d;
  }
  return 0;
}

/** yyyy-MM-dd in India time, so streaks roll over at local midnight. */
function dayKey(millis: number): string {
  return new Date(millis + 330 * 60 * 1000).toISOString().slice(0, 10);
}

export const dailyCheckIn = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const ref = db.collection("users").doc(uid);
  const now = Date.now();
  const today = dayKey(now);
  const yesterday = dayKey(now - DAY);
  return db.runTransaction(async (tx) => {
    const user = await tx.get(ref);
    if (!user.exists) throw new HttpsError("failed-precondition", "Finish setting up your profile first.");
    const last = user.get("lastCheckInDay") as string | undefined;
    const current = (user.get("streakDays") as number | undefined) ?? 0;
    if (last === today) return { streak: current, auraGained: 0, alreadyCheckedIn: true, boxUnlocked: false };
    const streak = last === yesterday ? current + 1 : 1;
    const plus = hasPlusPerks(user.get("membershipPlan"), user.get("membershipExpiresAt"));
    const gained = CHECK_IN_AURA * (plus ? 2 : 1) + (streak % 7 === 0 ? WEEK_BONUS_AURA : 0);
    const aura = Math.max(0, Math.min(AURA_MAX, ((user.get("auraPoints") as number | undefined) ?? 0) + gained));
    tx.update(ref, { streakDays: streak, lastCheckInDay: today, auraPoints: aura });
    return { streak, auraGained: gained, alreadyCheckedIn: false, boxUnlocked: true };
  });
});

export const openMysteryBox = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const ref = db.collection("users").doc(uid);
  const now = Date.now();
  const today = dayKey(now);
  const boxRef = ref.collection("boxes").doc(today);
  return db.runTransaction(async (tx) => {
    const [user, box] = await Promise.all([tx.get(ref), tx.get(boxRef)]);
    if (user.get("lastCheckInDay") !== today) throw new HttpsError("failed-precondition", "Check in first to unlock today's box.");
    const allowed = hasPlusPerks(user.get("membershipPlan"), user.get("membershipExpiresAt")) ? 2 : 1;
    const opened = (box.get("opened") as number | undefined) ?? 0;
    if (opened >= allowed) throw new HttpsError("resource-exhausted", "You've opened today's boxes. New ones drop at midnight ✨");
    tx.set(boxRef, { opened: opened + 1 }, { merge: true });

    const roll = Math.floor(Math.random() * 100);
    const expiresAt = now + COUPON_DAYS * DAY;
    let aura = 0;
    let coupon: (Coupon & { id: string }) | null = null;
    if (roll < 40) aura = 10;
    else if (roll < 60) aura = 25;
    else if (roll < 65) aura = 50;
    else {
      const couponRef = ref.collection("coupons").doc();
      const base = { maxDiscount: 0, expiresAt, used: false };
      const c: Coupon = roll < 85
        ? { ...base, kind: "Flat", value: 50, minOrder: 499 }
        : roll < 95
          ? { ...base, kind: "Percent", value: 10, minOrder: 999, maxDiscount: 200 }
          : { ...base, kind: "FreeDelivery", value: 0, minOrder: 0 };
      tx.set(couponRef, c);
      coupon = { ...c, id: couponRef.id };
    }
    if (aura > 0) {
      const next = Math.max(0, Math.min(AURA_MAX, ((user.get("auraPoints") as number | undefined) ?? 0) + aura));
      tx.update(ref, { auraPoints: next });
    }
    return { aura, coupon };
  });
});

/**
 * Starts or renews a membership for 30 days. Only available while payments are simulated:
 * Play Store builds must sell subscriptions through Google Play Billing, whose server-side
 * purchase verification replaces this function at release.
 */
export const startMembership = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const plan = String(request.data?.plan ?? "");
  if (!PLANS.includes(plan)) throw new HttpsError("invalid-argument", "Pick a plan.");
  if (paymentsLive()) throw new HttpsError("failed-precondition", "Memberships are sold through Google Play in this version.");
  const ref = db.collection("users").doc(uid);
  await db.runTransaction(async (tx) => {
    const user = await tx.get(ref);
    const now = Date.now();
    const currentEnd = Number(user.get("membershipExpiresAt") ?? 0);
    const base = user.get("membershipPlan") === plan && currentEnd > now ? currentEnd : now;
    tx.update(ref, { membershipPlan: plan, membershipExpiresAt: base + MEMBERSHIP_DAYS * DAY });
  });
  return { plan };
});

export const cancelMembership = onCall(async (request) => {
  const uid = requireUid(request.auth);
  await db.collection("users").doc(uid).update({ membershipPlan: "None", membershipExpiresAt: 0 });
  return { plan: "None" };
});

// ---------------------------------------------------------------- Near

/** People who haven't refreshed Near in a day disappear from it. */
export const cleanUpNear = onSchedule("every 60 minutes", async () => {
  const stale = await db.collection("near").where("updatedAt", "<=", Timestamp.fromMillis(Date.now() - DAY)).limit(400).get();
  await Promise.all(stale.docs.map((d) => d.ref.delete()));
  if (!stale.empty) logger.info(`Removed ${stale.size} stale Near entries`);
});

/** Going private (or seller → personal) immediately hides the user from Near. */
export const onUserUpdated = onDocumentUpdated("users/{uid}", async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after) return;
  const { uid } = event.params;
  if (!before.isPrivate && after.isPrivate) await db.collection("near").doc(uid).delete().catch(() => undefined);
  if (before.accountMode === "Seller" && after.accountMode !== "Seller") await db.collection("stores").doc(uid).delete().catch(() => undefined);
});

// ---------------------------------------------------------------- Google Play Billing (memberships)

/**
 * Play Store builds sell memberships as Google Play subscriptions. The app sends the purchase
 * token here; we verify it with the Play Developer API (the functions' service account needs
 * "View financial data" + "Manage orders" access in Play Console) and grant the plan until the
 * subscription's expiry. Renewals and cancellations arrive as Real-time Developer Notifications
 * on the Pub/Sub topic `play-billing`.
 */
const PLAY_PRODUCTS: Record<string, string> = {
  onefera_plus_monthly: "Plus",
  onefera_seller_pro_monthly: "SellerPro",
};
const ANDROID_PACKAGE = process.env.ANDROID_PACKAGE ?? "com.onefera.app";

interface PlaySubscription {
  subscriptionState?: string;
  lineItems?: Array<{ productId: string; expiryTime?: string }>;
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string };
}

/** The app passes this as the obfuscated account id, binding a purchase to one OneFera user. */
function accountHash(uid: string): string {
  return createHash("sha256").update(uid).digest("hex").slice(0, 64);
}

async function fetchPlaySubscription(token: string): Promise<PlaySubscription> {
  const auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
  const client = await auth.getClient();
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${ANDROID_PACKAGE}` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`;
  const res = await client.request<PlaySubscription>({ url });
  return res.data;
}

/** Applies a Play subscription's current state to the user. Returns the plan granted (or "None"). */
async function applyPlaySubscription(uid: string, token: string, sub: PlaySubscription): Promise<{ plan: string; expiresAt: number }> {
  const active = sub.subscriptionState === "SUBSCRIPTION_STATE_ACTIVE" || sub.subscriptionState === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ||
    sub.subscriptionState === "SUBSCRIPTION_STATE_CANCELED"; // cancelled = no renewal, still paid until expiry
  const item = (sub.lineItems ?? []).find((l) => PLAY_PRODUCTS[l.productId]);
  const expiresAt = item?.expiryTime ? Date.parse(item.expiryTime) : 0;
  const plan = active && item && expiresAt > Date.now() ? PLAY_PRODUCTS[item.productId] : "None";
  await db.runTransaction(async (tx) => {
    const ref = db.collection("playPurchases").doc(createHash("sha256").update(token).digest("hex"));
    const existing = await tx.get(ref);
    if (existing.exists && existing.get("uid") !== uid) {
      throw new HttpsError("permission-denied", "This purchase belongs to another account.");
    }
    tx.set(ref, { uid, token, productId: item?.productId ?? "", state: sub.subscriptionState ?? "", expiresAt, updatedAt: Timestamp.now() });
    tx.update(db.collection("users").doc(uid), plan === "None"
      ? { membershipPlan: "None", membershipExpiresAt: 0 }
      : { membershipPlan: plan, membershipExpiresAt: expiresAt });
  });
  return { plan, expiresAt };
}

export const verifyPlaySubscription = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const productId = String(request.data?.productId ?? "");
  const token = String(request.data?.purchaseToken ?? "");
  if (!PLAY_PRODUCTS[productId] || !token) throw new HttpsError("invalid-argument", "Unknown purchase.");
  let sub: PlaySubscription;
  try {
    sub = await fetchPlaySubscription(token);
  } catch (e) {
    logger.error("Play verification failed", e);
    throw new HttpsError("unavailable", "We couldn't confirm your purchase with Google Play yet. It'll update shortly.");
  }
  const owner = sub.externalAccountIdentifiers?.obfuscatedExternalAccountId;
  if (owner && owner !== accountHash(uid)) throw new HttpsError("permission-denied", "This purchase belongs to another account.");
  return applyPlaySubscription(uid, token, sub);
});

/** Real-time Developer Notifications: renewals, cancellations, expiries, refunds. */
export const onPlayNotification = onMessagePublished("play-billing", async (event) => {
  const payload = event.data.message.json as { subscriptionNotification?: { purchaseToken?: string } } | undefined;
  const token = payload?.subscriptionNotification?.purchaseToken;
  if (!token) return;
  const record = await db.collection("playPurchases").doc(createHash("sha256").update(token).digest("hex")).get();
  const uid = record.get("uid") as string | undefined;
  if (!uid) return; // not yet linked; the app verifies on purchase
  try {
    await applyPlaySubscription(uid, token, await fetchPlaySubscription(token));
  } catch (e) {
    logger.error("Couldn't refresh Play subscription", e);
  }
});

// ---------------------------------------------------------------- account deletion

/**
 * In-app account deletion (Google Play requirement). The app re-authenticates first. Deletes the
 * profile and everything under it, the username claim, posts (media cleanup runs via
 * onPostDeleted), stories, listings, Near entries and avatar files, then the Auth user.
 * Orders stay for tax/refund records but lose the buyer's address.
 */
export const deleteAccount = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const userRef = db.collection("users").doc(uid);
  const username = (await userRef.get()).get("username") as string | undefined;

  const owned = await Promise.all([
    db.collection("posts").where("authorId", "==", uid).get(),
    db.collection("stories").where("authorId", "==", uid).get(),
    db.collection("products").where("sellerId", "==", uid).get(),
  ]);
  for (const snap of owned) {
    for (const doc of snap.docs) await db.recursiveDelete(doc.ref);
  }
  const orders = await db.collection("orders").where("buyerId", "==", uid).get();
  await Promise.all(orders.docs.map((d) => d.ref.update({ address: { name: "Deleted user", phone: "", line1: "", line2: "", city: "", state: "", pincode: "" } })));

  await Promise.all([
    db.collection("near").doc(uid).delete().catch(() => undefined),
    db.collection("stores").doc(uid).delete().catch(() => undefined),
    username ? db.collection("usernames").doc(username).delete().catch(() => undefined) : Promise.resolve(),
    getStorage().bucket().deleteFiles({ prefix: `avatars/${uid}/` }).catch(() => undefined),
    getStorage().bucket().deleteFiles({ prefix: `products/${uid}/` }).catch(() => undefined),
  ]);
  await db.recursiveDelete(userRef);
  await getAuth().deleteUser(uid);
  logger.info(`Deleted account ${uid}`);
  return { deleted: true };
});

// ---------------------------------------------------------------- moderation

/** Posts reported by this many different people are hidden from feeds until a moderator reviews them. */
const AUTO_HIDE_REPORTS = 3;

export const onReportCreated = onDocumentCreated("reports/{reportId}", async (event) => {
  const report = event.data?.data();
  if (!report) return;
  logger.info(`Report ${event.params.reportId}: ${report.targetType} ${report.targetId} for ${report.reason}`);
  if (report.targetType !== "Post") return;
  const reports = await db.collection("reports")
    .where("targetType", "==", "Post").where("targetId", "==", report.targetId).limit(50).get();
  const reporters = new Set(reports.docs.map((d) => d.get("reporterId") as string));
  // Self-harm reports hide immediately so a moderator can reach out first.
  if (reporters.size >= AUTO_HIDE_REPORTS || report.reason === "SelfHarm") {
    await db.collection("posts").doc(report.targetId).update({ hidden: true }).catch(() => undefined);
  }
});
