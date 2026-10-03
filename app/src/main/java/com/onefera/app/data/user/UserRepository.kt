package com.onefera.app.data.user

import android.net.Uri
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.ProfileUpdate
import com.onefera.app.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /** Live profile updates; emits null while the profile doesn't exist yet. */
    fun observeProfile(uid: String): Flow<UserProfile?>

    suspend fun isUsernameAvailable(username: String, forUid: String? = null): Boolean

    /** Creates `users/{uid}` and claims the username atomically. */
    suspend fun createProfile(profile: UserProfile): Result<Unit>

    suspend fun updateProfile(uid: String, update: ProfileUpdate): Result<Unit>

    /** Uploads a new profile photo and returns its URL. */
    suspend fun uploadAvatar(uid: String, image: Uri): Result<String>

    suspend fun setAccountMode(uid: String, mode: AccountMode): Result<Unit>

    suspend fun setPrivate(uid: String, isPrivate: Boolean): Result<Unit>

    /** Marks the user as active right now (drives "Active now" in chats). */
    suspend fun touchLastActive(uid: String)
}
