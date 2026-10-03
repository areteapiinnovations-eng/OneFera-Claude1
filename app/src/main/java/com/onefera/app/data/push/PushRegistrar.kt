package com.onefera.app.data.push

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this device's FCM token in `users/{uid}/fcmTokens/{token}` while the user is signed in and
 * has push notifications switched on, and removes it when they sign out or turn push off.
 * Does nothing in demo mode.
 */
@Singleton
class PushRegistrar @Inject constructor(
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val config: BackendConfig,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var registeredFor: String? = null
    private var token: String? = null

    fun start() {
        if (!config.isFirebaseEnabled) return
        scope.launch {
            combine(
                auth.session.map { (it as? SessionState.SignedIn)?.uid },
                settings.settings.map { it.pushEnabled },
            ) { uid, enabled -> uid to enabled }
                .distinctUntilChanged()
                .collect { (uid, enabled) -> if (uid != null && enabled) register(uid) else unregister() }
        }
    }

    /** Called by the messaging service when FCM rotates the token. */
    fun onNewToken(newToken: String) {
        if (!config.isFirebaseEnabled) return
        scope.launch {
            val uid = (auth.session.value as? SessionState.SignedIn)?.uid ?: return@launch
            token = newToken
            save(uid, newToken)
        }
    }

    /** Removes the token before signing out, while the user is still authenticated. */
    suspend fun unregister() {
        val uid = registeredFor ?: return
        val t = token ?: return
        runCatching { FirebaseFirestore.getInstance().collection("users").document(uid).collection("fcmTokens").document(t).delete().await() }
        registeredFor = null
    }

    private suspend fun register(uid: String) {
        val t = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        token = t
        save(uid, t)
    }

    private suspend fun save(uid: String, t: String) {
        runCatching {
            FirebaseFirestore.getInstance().collection("users").document(uid).collection("fcmTokens").document(t)
                .set(mapOf("platform" to "android", "updatedAt" to FieldValue.serverTimestamp())).await()
            registeredFor = uid
        }
    }
}
