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
