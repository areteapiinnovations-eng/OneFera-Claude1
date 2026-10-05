package com.onefera.app.data.auth

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
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

    override suspend fun deleteAccount(password: String): Result<Unit> = runAuth {
        val user = auth.currentUser ?: throw UserFacingException("Please log in again.")
        // Firebase requires a recent sign-in for destructive actions.
        user.reauthenticate(EmailAuthProvider.getCredential(user.email.orEmpty(), password)).await()
        try {
            FirebaseFunctions.getInstance("asia-south1").getHttpsCallable("deleteAccount").call().await()
        } catch (e: FirebaseFunctionsException) {
            throw UserFacingException(e.message ?: "Couldn't delete your account. Try again.", e)
        }
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
        Log.e(TAG, "Firebase Auth request failed", e)
        Result.failure(UserFacingException(e.toFriendlyMessage(), e))
    }

    private fun Exception.toFriendlyMessage(): String {
        val code = (this as? FirebaseAuthException)?.errorCode.orEmpty()
        val text = "$code ${message.orEmpty()}"
        return when {
            this is FirebaseAuthWeakPasswordException -> "That password is too easy to guess. Try a stronger one."
            this is FirebaseAuthUserCollisionException -> "An account with this email already exists. Try signing in."
            this is FirebaseAuthInvalidUserException -> "We couldn't find an account with that email."
            this is FirebaseAuthInvalidCredentialsException -> "Email or password is incorrect."
            this is FirebaseTooManyRequestsException -> "Too many attempts. Take a breather and try again soon."
            this is FirebaseNetworkException -> "You're offline. Check your connection and try again."
            // Setup problems in the Firebase project, worded so whoever runs the project can fix them.
            "OPERATION_NOT_ALLOWED" in text ->
                "Email sign-up isn't enabled for this app yet (Firebase: Authentication → Sign-in method → Email/Password)."
            "CONFIGURATION_NOT_FOUND" in text ->
                "Sign-in isn't set up for this app yet (Firebase: Authentication → Get started)."
            "API key" in text || "API_KEY" in text ->
                "This app's Firebase API key was rejected. Check the key's restrictions in Google Cloud → Credentials."
            "app-check" in text.lowercase() || "recaptcha" in text.lowercase() || "app attestation" in text.lowercase() ->
                "This app couldn't be verified by Firebase (App Check / reCAPTCHA). Check the project's App Check settings."
            else -> "Something went wrong. Please try again." + (code.ifEmpty { null }?.let { " ($it)" } ?: "")
        }
    }

    private companion object {
        const val TAG = "OneFeraAuth"
    }
}
