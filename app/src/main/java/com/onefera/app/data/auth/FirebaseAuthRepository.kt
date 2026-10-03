package com.onefera.app.data.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.UserFacingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    override val session: StateFlow<SessionState> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            trySend(if (user == null) SessionState.SignedOut else SessionState.SignedIn(user.uid, user.email.orEmpty()))
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Unknown)

    override suspend fun signIn(email: String, password: String): Result<Unit> = runAuth {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        Unit
    }

    override suspend fun signUp(email: String, password: String): Result<String> = runAuth {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        result.user?.uid ?: throw UserFacingException("Couldn't create your account. Try again.")
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = runAuth {
        auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    override suspend fun deleteCurrentAccount() {
        runCatching { auth.currentUser?.delete()?.await() }
    }

    private suspend fun <T> runAuth(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: UserFacingException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(UserFacingException(e.toFriendlyMessage(), e))
    }

    private fun Exception.toFriendlyMessage(): String = when (this) {
        is FirebaseAuthWeakPasswordException -> "That password is too easy to guess. Try a stronger one."
        is FirebaseAuthUserCollisionException -> "An account with this email already exists. Try signing in."
        is FirebaseAuthInvalidUserException -> "We couldn't find an account with that email."
        is FirebaseAuthInvalidCredentialsException -> "Email or password is incorrect."
        is FirebaseTooManyRequestsException -> "Too many attempts. Take a breather and try again soon."
        is FirebaseNetworkException -> "You're offline. Check your connection and try again."
        else -> "Something went wrong. Please try again."
    }
}
