package com.onefera.app.data.user

import android.net.Uri
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.ProfileUpdate
import com.onefera.app.data.model.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore layout:
 * - `users/{uid}`          public profile (counters are server-owned, see firebase/firestore.rules)
 * - `usernames/{username}` `{ uid }`, guarantees unique handles
 * Profile photos live in Cloud Storage at `avatars/{uid}/avatar.jpg`.
 */
@Singleton
class FirestoreUserRepository @Inject constructor() : UserRepository {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    private fun userDoc(uid: String) = db.collection(USERS).document(uid)
    private fun usernameDoc(username: String) = db.collection(USERNAMES).document(username)

    override fun observeProfile(uid: String): Flow<UserProfile?> = callbackFlow {
        val registration = userDoc(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Keep the stream alive; the UI shows the last known profile.
                return@addSnapshotListener
            }
            trySend(snapshot?.takeIf { it.exists() }?.toUserProfile())
        }
        awaitClose { registration.remove() }
    }

    override suspend fun isUsernameAvailable(username: String, forUid: String?): Boolean {
        val snapshot = usernameDoc(username).get().await()
        return !snapshot.exists() || (forUid != null && snapshot.getString("uid") == forUid)
    }

    override suspend fun createProfile(profile: UserProfile): Result<Unit> = runCatchingFriendly {
        db.runTransaction { tx ->
            val claim = tx.get(usernameDoc(profile.username))
            if (claim.exists() && claim.getString("uid") != profile.uid) {
                throw UserFacingException("@${profile.username} was just taken. Try another one.")
            }
            tx.set(usernameDoc(profile.username), mapOf("uid" to profile.uid))
            tx.set(userDoc(profile.uid), profile.toMap() + ("createdAt" to FieldValue.serverTimestamp()))
        }.await()
        Unit
    }

    override suspend fun updateProfile(uid: String, update: ProfileUpdate): Result<Unit> = runCatchingFriendly {
        db.runTransaction { tx ->
            val current = tx.get(userDoc(uid))
            val oldUsername = current.getString("username").orEmpty()
            if (update.username != oldUsername) {
                val claim = tx.get(usernameDoc(update.username))
                if (claim.exists() && claim.getString("uid") != uid) {
                    throw UserFacingException("@${update.username} is taken. Try another one.")
                }
                tx.set(usernameDoc(update.username), mapOf("uid" to uid))
                if (oldUsername.isNotEmpty()) tx.delete(usernameDoc(oldUsername))
            }
            tx.update(
                userDoc(uid),
                mapOf(
                    "displayName" to update.displayName,
                    "displayNameLower" to update.displayName.lowercase(),
                    "username" to update.username,
                    "bio" to update.bio,
                    "vibe" to update.vibe,
                    "city" to update.city,
                ),
            )
        }.await()
        Unit
    }

    override suspend fun uploadAvatar(uid: String, image: Uri): Result<String> = runCatchingFriendly {
        val ref = storage.reference.child("avatars/$uid/avatar.jpg")
        ref.putFile(image).await()
        val url = ref.downloadUrl.await().toString()
        userDoc(uid).update("avatarUrl", url).await()
        url
    }

    override suspend fun setAccountMode(uid: String, mode: AccountMode): Result<Unit> = runCatchingFriendly {
        userDoc(uid).update("accountMode", mode.name).await()
        Unit
    }

    override suspend fun setPrivate(uid: String, isPrivate: Boolean): Result<Unit> = runCatchingFriendly {
        userDoc(uid).update("isPrivate", isPrivate).await()
        Unit
    }

    private suspend fun <T> runCatchingFriendly(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: UserFacingException) {
        Result.failure(e)
    } catch (e: Exception) {
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is UserFacingException }
        Result.failure(cause ?: UserFacingException("Couldn't save right now. Check your connection and try again.", e))
    }

    private companion object {
        const val USERS = "users"
        const val USERNAMES = "usernames"
    }
}

private fun UserProfile.toMap(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "displayName" to displayName,
    "displayNameLower" to displayName.lowercase(),
    "username" to username,
    "email" to email,
    "bio" to bio,
    "avatarUrl" to avatarUrl,
    "vibe" to vibe,
    "city" to city,
    "birthDate" to birthDate,
    "isPrivate" to isPrivate,
    "isMinor" to isMinor,
    "accountMode" to accountMode.name,
    "verified" to false,
    "auraPoints" to 0,
    "streakDays" to 0,
    "postsCount" to 0,
    "followersCount" to 0,
    "followingCount" to 0,
    "profileViews" to 0,
)

private fun DocumentSnapshot.toUserProfile(): UserProfile = UserProfile(
    uid = id,
    displayName = getString("displayName").orEmpty(),
    username = getString("username").orEmpty(),
    email = getString("email").orEmpty(),
    bio = getString("bio").orEmpty(),
    avatarUrl = getString("avatarUrl"),
    vibe = getString("vibe").orEmpty(),
    city = getString("city").orEmpty(),
    birthDate = getString("birthDate").orEmpty(),
    isPrivate = getBoolean("isPrivate") ?: false,
    isMinor = getBoolean("isMinor") ?: false,
    accountMode = runCatching { AccountMode.valueOf(getString("accountMode").orEmpty()) }.getOrDefault(AccountMode.Personal),
    verified = getBoolean("verified") ?: false,
    auraPoints = getLong("auraPoints")?.toInt() ?: 0,
    streakDays = getLong("streakDays")?.toInt() ?: 0,
    postsCount = getLong("postsCount")?.toInt() ?: 0,
    followersCount = getLong("followersCount")?.toInt() ?: 0,
    followingCount = getLong("followingCount")?.toInt() ?: 0,
    profileViews = getLong("profileViews")?.toInt() ?: 0,
    createdAt = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
)
