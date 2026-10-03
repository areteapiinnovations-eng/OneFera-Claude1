// Firestore security-rules tests. Run with `npm test` (starts the emulators).
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, updateDoc, writeBatch } from 'firebase/firestore';

let env;

const profile = (uid, extra = {}) => ({
  uid,
  displayName: 'Test User',
  displayNameLower: 'test user',
  username: `user_${uid}`,
  email: `${uid}@test.dev`,
  bio: '',
  avatarUrl: null,
  vibe: '',
  city: '',
  birthDate: '2004-01-01',
  isPrivate: false,
  isMinor: false,
  accountMode: 'Personal',
  verified: false,
  auraPoints: 0,
  streakDays: 0,
  postsCount: 0,
  followersCount: 0,
  followingCount: 0,
  profileViews: 0,
  ...extra,
});

const summary = (uid) => ({ uid, displayName: 'Test User', username: `user_${uid}`, avatarUrl: null, verified: false, isPrivate: false });

const post = (authorId, extra = {}) => ({
  authorId,
  author: summary(authorId),
  type: 'Post',
  caption: 'hello #onefera',
  media: [{ url: 'https://x/y.jpg', type: 'Image', thumbnailUrl: null, aspectRatio: 0.8 }],
  location: '',
  tags: ['onefera'],
  soundName: '',
  likeCount: 0,
  commentCount: 0,
  shareCount: 0,
  visibility: 'public',
  createdAt: serverTimestamp(),
  ...extra,
});

const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-onefera',
    firestore: { rules: readFileSync('../firestore.rules', 'utf8') },
  });
});

after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());

describe('profiles', () => {
  it('lets a user create their own clean profile', async () => {
    await assertSucceeds(setDoc(doc(db('alice'), 'users/alice'), profile('alice')));
  });

  it('rejects a profile that grants itself Aura or followers', async () => {
    await assertFails(setDoc(doc(db('alice'), 'users/alice'), profile('alice', { auraPoints: 999 })));
    await assertFails(setDoc(doc(db('alice'), 'users/alice'), profile('alice', { followersCount: 10 })));
  });

  it('rejects writing someone else\'s profile', async () => {
    await assertFails(setDoc(doc(db('mallory'), 'users/alice'), profile('alice')));
  });

  it('blocks clients from editing server-owned counters', async () => {
    await seed((f) => setDoc(doc(f, 'users/alice'), profile('alice')));
    await assertFails(updateDoc(doc(db('alice'), 'users/alice'), { followersCount: 1000 }));
    await assertSucceeds(updateDoc(doc(db('alice'), 'users/alice'), { bio: 'new bio' }));
  });
});

describe('follows', () => {
  beforeEach(async () => {
    await seed(async (f) => {
      await setDoc(doc(f, 'users/alice'), profile('alice'));
      await setDoc(doc(f, 'users/bob'), profile('bob'));
      await setDoc(doc(f, 'users/priv'), profile('priv', { isPrivate: true }));
    });
  });

  it('lets anyone follow a public account', async () => {
    const f = db('bob');
    const batch = writeBatch(f);
    batch.set(doc(f, 'users/bob/following/alice'), { ...summary('alice'), createdAt: serverTimestamp() });
    batch.set(doc(f, 'users/alice/followers/bob'), { ...summary('bob'), createdAt: serverTimestamp() });
    await assertSucceeds(batch.commit());
  });

  it('does not let you add yourself as a follower of a private account', async () => {
    await assertFails(setDoc(doc(db('bob'), 'users/priv/followers/bob'), summary('bob')));
  });

  it('lets a private account approve a pending request', async () => {
    await assertSucceeds(setDoc(doc(db('bob'), 'users/priv/followRequests/bob'), summary('bob')));
    const f = db('priv');
    const batch = writeBatch(f);
    batch.set(doc(f, 'users/priv/followers/bob'), { ...summary('bob'), viaRequest: true });
    batch.set(doc(f, 'users/bob/following/priv'), summary('priv'));
    batch.delete(doc(f, 'users/priv/followRequests/bob'));
    await assertSucceeds(batch.commit());
  });

  it('does not let an account force someone to follow it', async () => {
    await assertFails(setDoc(doc(db('priv'), 'users/bob/following/priv'), summary('priv')));
  });
});

describe('posts', () => {
  beforeEach(async () => {
    await seed(async (f) => {
      await setDoc(doc(f, 'users/alice'), profile('alice'));
      await setDoc(doc(f, 'users/bob'), profile('bob'));
    });
  });

  it('lets an author create a valid post', async () => {
    await assertSucceeds(setDoc(doc(db('alice'), 'posts/p1'), post('alice')));
  });

  it('rejects posts with fake counters or for another author', async () => {
    await assertFails(setDoc(doc(db('alice'), 'posts/p1'), post('alice', { likeCount: 500 })));
    await assertFails(setDoc(doc(db('alice'), 'posts/p2'), post('bob')));
  });

  it('hides followers-only posts from non-followers', async () => {
    await seed((f) => setDoc(doc(f, 'posts/p1'), post('alice', { visibility: 'followers' })));
    await assertFails(getDoc(doc(db('bob'), 'posts/p1')));
    await seed((f) => setDoc(doc(f, 'users/alice/followers/bob'), summary('bob')));
    await assertSucceeds(getDoc(doc(db('bob'), 'posts/p1')));
  });

  it('allows liking but not editing counters directly', async () => {
    await seed((f) => setDoc(doc(f, 'posts/p1'), post('alice')));
    await assertSucceeds(setDoc(doc(db('bob'), 'posts/p1/likes/bob'), { uid: 'bob', createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db('bob'), 'posts/p1/likes/alice'), { uid: 'alice' }));
    await assertFails(updateDoc(doc(db('bob'), 'posts/p1'), { likeCount: 1 }));
  });

  it('validates comments', async () => {
    await seed((f) => setDoc(doc(f, 'posts/p1'), post('alice')));
    const ok = { authorId: 'bob', author: summary('bob'), text: 'fire 🔥', createdAt: serverTimestamp() };
    await assertSucceeds(setDoc(doc(db('bob'), 'posts/p1/comments/c1'), ok));
    await assertFails(setDoc(doc(db('bob'), 'posts/p1/comments/c2'), { ...ok, text: '' }));
    await assertFails(setDoc(doc(db('bob'), 'posts/p1/comments/c3'), { ...ok, authorId: 'alice', author: summary('alice') }));
  });

  it('only lets the author delete a post', async () => {
    await seed((f) => setDoc(doc(f, 'posts/p1'), post('alice')));
    await assertFails(deleteDoc(doc(db('bob'), 'posts/p1')));
    await assertSucceeds(deleteDoc(doc(db('alice'), 'posts/p1')));
  });
});

describe('notifications', () => {
  it('cannot be forged by clients but can be marked read by the owner', async () => {
    await assertFails(setDoc(doc(db('bob'), 'users/alice/notifications/n1'), { type: 'Like', read: false }));
    await seed((f) => setDoc(doc(f, 'users/alice/notifications/n1'), { type: 'Like', read: false, text: 'hi' }));
    await assertFails(updateDoc(doc(db('alice'), 'users/alice/notifications/n1'), { text: 'changed' }));
    await assertSucceeds(updateDoc(doc(db('alice'), 'users/alice/notifications/n1'), { read: true }));
    await assertFails(getDoc(doc(db('bob'), 'users/alice/notifications/n1')));
  });
});

describe('stories', () => {
  it('rejects stories that live longer than a day', async () => {
    const base = { authorId: 'alice', author: summary('alice'), mediaUrl: 'https://x/s.jpg', createdAt: serverTimestamp() };
    await assertSucceeds(setDoc(doc(db('alice'), 'stories/s1'), { ...base, expiresAt: Date.now() + 24 * 3600 * 1000 }));
    await assertFails(setDoc(doc(db('alice'), 'stories/s2'), { ...base, expiresAt: Date.now() + 72 * 3600 * 1000 }));
  });
});

describe('chat', () => {
  const cid = 'alice_bob';
  const convo = {
    memberIds: ['alice', 'bob'],
    members: { alice: summary('alice'), bob: summary('bob') },
    lastMessage: '',
    lastSenderId: '',
    lastMessageAt: 0,
    unreadCounts: { alice: 0, bob: 0 },
  };
  const msg = (senderId, extra = {}) => ({ senderId, text: 'hey 👋', unsent: false, createdAt: serverTimestamp(), ...extra });

  it('lets a member start a one-to-one chat with a correctly formed id', async () => {
    await assertSucceeds(setDoc(doc(db('alice'), `conversations/${cid}`), convo));
    await assertFails(setDoc(doc(db('alice'), 'conversations/bob_alice'), convo));
    await assertFails(setDoc(doc(db('mallory'), `conversations/${cid}`), convo));
  });

  it('keeps conversations and messages private to members', async () => {
    await seed(async (f) => {
      await setDoc(doc(f, `conversations/${cid}`), convo);
      await setDoc(doc(f, `conversations/${cid}/messages/m1`), msg('alice'));
    });
    await assertSucceeds(getDoc(doc(db('bob'), `conversations/${cid}/messages/m1`)));
    await assertFails(getDoc(doc(db('mallory'), `conversations/${cid}`)));
    await assertFails(getDoc(doc(db('mallory'), `conversations/${cid}/messages/m1`)));
    await assertFails(setDoc(doc(db('mallory'), `conversations/${cid}/messages/m2`), msg('mallory')));
  });

  it('only lets you send as yourself', async () => {
    await seed((f) => setDoc(doc(f, `conversations/${cid}`), convo));
    await assertSucceeds(setDoc(doc(db('bob'), `conversations/${cid}/messages/m1`), msg('bob')));
    await assertFails(setDoc(doc(db('bob'), `conversations/${cid}/messages/m2`), msg('alice')));
    await assertFails(setDoc(doc(db('bob'), `conversations/${cid}/messages/m3`), msg('bob', { text: '' })));
  });

  it('lets members mark their own messages read but not fake previews or others\' counts', async () => {
    await seed((f) => setDoc(doc(f, `conversations/${cid}`), { ...convo, unreadCounts: { alice: 3, bob: 0 } }));
    await assertSucceeds(updateDoc(doc(db('alice'), `conversations/${cid}`), { 'unreadCounts.alice': 0, 'lastReadAt.alice': Date.now() }));
    await assertFails(updateDoc(doc(db('alice'), `conversations/${cid}`), { 'unreadCounts.bob': 5 }));
    await assertFails(updateDoc(doc(db('alice'), `conversations/${cid}`), { lastMessage: 'fake' }));
    await assertSucceeds(updateDoc(doc(db('bob'), `conversations/${cid}`), { 'typing.bob': Date.now() }));
    await assertFails(updateDoc(doc(db('bob'), `conversations/${cid}`), { 'typing.alice': Date.now() }));
  });

  it('only lets senders unsend their own messages', async () => {
    await seed(async (f) => {
      await setDoc(doc(f, `conversations/${cid}`), convo);
      await setDoc(doc(f, `conversations/${cid}/messages/m1`), msg('alice'));
    });
    await assertFails(updateDoc(doc(db('bob'), `conversations/${cid}/messages/m1`), { unsent: true, text: '', attachment: null }));
    await assertSucceeds(updateDoc(doc(db('alice'), `conversations/${cid}/messages/m1`), { unsent: true, text: '', attachment: null }));
  });
});

describe('shop', () => {
  const product = { id: 'p-1', title: 'Neon Kicks', brand: 'OneFera', imageUrl: null, price: 1999, mrp: 2999, sellerId: '' };
  const cartLine = (extra = {}) => ({ product, variant: '', quantity: 1, addedAt: serverTimestamp(), ...extra });
  const address = { name: 'Asha', phone: '9876543210', line1: '12 MG Road', line2: '', city: 'Pune', state: 'MH', pincode: '411001' };

  it('lets anyone signed in browse products but nobody write them', async () => {
    await seed((f) => setDoc(doc(f, 'products/p-1'), { title: 'Neon Kicks', price: 1999, stock: 5 }));
    await assertSucceeds(getDoc(doc(db('alice'), 'products/p-1')));
    await assertFails(getDoc(doc(db(null), 'products/p-1')));
    await assertFails(updateDoc(doc(db('alice'), 'products/p-1'), { price: 1 }));
    await assertFails(setDoc(doc(db('alice'), 'products/p-2'), { title: 'Free stuff', price: 0 }));
  });

  it('keeps carts and wishlists private and sane', async () => {
    await assertSucceeds(setDoc(doc(db('alice'), 'users/alice/cart/p-1'), cartLine()));
    await assertSucceeds(setDoc(doc(db('alice'), 'users/alice/cart/p-1__uk-8'), cartLine({ variant: 'UK 8' })));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/cart/p-1'), cartLine({ quantity: 50 })));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/cart/other'), cartLine()));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/cart/p-1'), cartLine({ discount: 100 })));
    await assertFails(setDoc(doc(db('bob'), 'users/alice/cart/p-1'), cartLine()));
    await assertFails(getDoc(doc(db('bob'), 'users/alice/cart/p-1')));
    await assertSucceeds(setDoc(doc(db('alice'), 'users/alice/wishlist/p-1'), { addedAt: serverTimestamp() }));
    await assertFails(getDoc(doc(db('bob'), 'users/alice/wishlist/p-1')));
  });

  it('validates saved addresses', async () => {
    await assertSucceeds(setDoc(doc(db('alice'), 'users/alice/addresses/default'), address));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/addresses/default'), { ...address, phone: '123' }));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/addresses/default'), { ...address, pincode: 'ABCDEF' }));
    await assertFails(getDoc(doc(db('bob'), 'users/alice/addresses/default')));
  });

  it('only shows buyers their own orders and never lets clients write them', async () => {
    await seed((f) => setDoc(doc(f, 'orders/o1'), { buyerId: 'alice', status: 'Placed', total: 1999 }));
    await assertSucceeds(getDoc(doc(db('alice'), 'orders/o1')));
    await assertFails(getDoc(doc(db('bob'), 'orders/o1')));
    await assertFails(updateDoc(doc(db('alice'), 'orders/o1'), { status: 'Delivered' }));
    await assertFails(setDoc(doc(db('alice'), 'orders/o2'), { buyerId: 'alice', status: 'Placed', total: 1 }));
  });

  it('allows up to five product tags on a post', async () => {
    const tags = Array.from({ length: 5 }, (_, i) => ({ ...product, id: `p-${i}` }));
    await assertSucceeds(setDoc(doc(db('alice'), 'posts/tagged'), post('alice', { products: tags })));
    await assertFails(setDoc(doc(db('alice'), 'posts/too-many'), post('alice', { products: [...tags, product] })));
  });
});

describe('seller mode', () => {
  const listing = (sellerId, extra = {}) => ({
    title: 'Hand-painted tote',
    brand: 'Meera Makes',
    description: 'One of a kind.',
    category: 'Fashion',
    price: 899,
    mrp: 1299,
    stock: 12,
    images: ['https://x/tote.jpg'],
    variants: [],
    highlights: ['Handmade'],
    keywords: ['ha', 'han'],
    sellerId,
    seller: summary(sellerId),
    isDrop: false,
    rating: 0,
    ratingCount: 0,
    soldCount: 0,
    createdAt: serverTimestamp(),
    ...extra,
  });

  async function accounts() {
    await seed(async (f) => {
      await setDoc(doc(f, 'users/sam'), profile('sam', { accountMode: 'Seller' }));
      await setDoc(doc(f, 'users/pat'), profile('pat'));
    });
  }

  it('lets seller accounts list valid products as themselves', async () => {
    await accounts();
    await assertSucceeds(setDoc(doc(db('sam'), 'products/tote'), listing('sam')));
    await assertFails(setDoc(doc(db('pat'), 'products/p1'), listing('pat')));
    await assertFails(setDoc(doc(db('sam'), 'products/p2'), listing('pat')));
    await assertFails(setDoc(doc(db('sam'), 'products/p3'), listing('sam', { soldCount: 500 })));
    await assertFails(setDoc(doc(db('sam'), 'products/p4'), listing('sam', { price: 1 })));
    await assertFails(setDoc(doc(db('sam'), 'products/p5'), listing('sam', { mrp: 100 })));
    await assertFails(setDoc(doc(db('sam'), 'products/p6'), listing('sam', { isDrop: true })));
    await assertFails(setDoc(doc(db('sam'), 'products/p7'), listing('sam', { images: [] })));
  });

  it('lets sellers edit stock and price but not sales counters or other sellers listings', async () => {
    await accounts();
    await seed((f) => setDoc(doc(f, 'products/tote'), listing('sam', { soldCount: 3 })));
    await assertSucceeds(updateDoc(doc(db('sam'), 'products/tote'), { stock: 40, price: 799 }));
    await assertFails(updateDoc(doc(db('sam'), 'products/tote'), { soldCount: 999 }));
    await assertFails(updateDoc(doc(db('sam'), 'products/tote'), { stock: -1 }));
    await assertFails(updateDoc(doc(db('pat'), 'products/tote'), { stock: 0 }));
    await assertFails(deleteDoc(doc(db('pat'), 'products/tote')));
    await assertSucceeds(deleteDoc(doc(db('sam'), 'products/tote')));
  });

  it('shows orders to the sellers in them, read-only', async () => {
    await seed((f) => setDoc(doc(f, 'orders/o9'), { buyerId: 'pat', sellerIds: ['sam'], status: 'Placed', total: 899 }));
    await assertSucceeds(getDoc(doc(db('sam'), 'orders/o9')));
    await assertSucceeds(getDoc(doc(db('pat'), 'orders/o9')));
    await assertFails(getDoc(doc(db('mallory'), 'orders/o9')));
    await assertFails(updateDoc(doc(db('sam'), 'orders/o9'), { status: 'Delivered' }));
  });
});

describe('rewards', () => {
  it('keeps streaks, memberships, coupons and boxes server-owned', async () => {
    await seed(async (f) => {
      await setDoc(doc(f, 'users/alice'), profile('alice'));
      await setDoc(doc(f, 'users/alice/coupons/c1'), { kind: 'Flat', value: 50, minOrder: 499, used: false, expiresAt: Date.now() + 1e6 });
      await setDoc(doc(f, 'users/alice/boxes/2026-10-03'), { opened: 1 });
    });
    await assertFails(updateDoc(doc(db('alice'), 'users/alice'), { streakDays: 99 }));
    await assertFails(updateDoc(doc(db('alice'), 'users/alice'), { lastCheckInDay: '2026-10-03' }));
    await assertFails(updateDoc(doc(db('alice'), 'users/alice'), { membershipPlan: 'Plus', membershipExpiresAt: 9e12 }));
    await assertSucceeds(getDoc(doc(db('alice'), 'users/alice/coupons/c1')));
    await assertFails(getDoc(doc(db('bob'), 'users/alice/coupons/c1')));
    await assertFails(updateDoc(doc(db('alice'), 'users/alice/coupons/c1'), { used: false, value: 5000 }));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/coupons/free'), { kind: 'Flat', value: 9999, minOrder: 0, used: false }));
    await assertSucceeds(getDoc(doc(db('alice'), 'users/alice/boxes/2026-10-03')));
    await assertFails(setDoc(doc(db('alice'), 'users/alice/boxes/2026-10-03'), { opened: 0 }));
  });
});
