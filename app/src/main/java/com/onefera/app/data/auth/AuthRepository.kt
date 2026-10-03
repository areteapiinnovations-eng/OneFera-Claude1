package com.onefera.app.data.auth

import kotlinx.coroutines.flow.StateFlow

/** Session state: [Unknown] until the backend has restored any saved session. */
sealed interface SessionState {
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val uid: String, val email: String) : SessionState
}

interface AuthRepository {
    val session: StateFlow<SessionState>

    suspend fun signIn(email: String, password: String): Result<Unit>

    /** Creates the account and returns its uid. */
    suspend fun signUp(email: String, password: String): Result<String>

    suspend fun sendPasswordReset(email: String): Result<Unit>

    suspend fun signOut()

    /** Removes the just-created account, used to roll back a sign-up whose profile failed to save. */
    suspend fun deleteCurrentAccount()
}
