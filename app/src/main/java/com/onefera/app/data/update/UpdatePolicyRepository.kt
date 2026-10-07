package com.onefera.app.data.update

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.onefera.app.data.backend.BackendConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Live release policy from `config/app` (readable by everyone, even before sign-in). */
@Singleton
class UpdatePolicyRepository @Inject constructor(private val config: BackendConfig) {

    fun policy(): Flow<UpdatePolicy?> = if (!config.isFirebaseEnabled) {
        flowOf(null) // Demo builds have no server to announce releases.
    } else {
        callbackFlow {
            val registration = FirebaseFirestore.getInstance().collection("config").document("app")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Couldn't read the update policy", error)
                        trySend(null)
                    } else if (snapshot != null) {
                        trySend(
                            if (!snapshot.exists()) {
                                null
                            } else {
                                UpdatePolicy(
                                    minVersionCode = snapshot.getLong("minVersionCode")?.toInt() ?: 0,
                                    latestVersionCode = snapshot.getLong("latestVersionCode")?.toInt() ?: 0,
                                    message = snapshot.getString("message").orEmpty(),
                                    updateUrl = snapshot.getString("updateUrl").orEmpty(),
                                )
                            },
                        )
                    }
                }
            awaitClose { registration.remove() }
        }
    }

    private companion object {
        const val TAG = "OneFeraUpdate"
    }
}
