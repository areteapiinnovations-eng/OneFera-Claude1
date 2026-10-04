package com.onefera.app.data.backend

import android.content.Context
import com.google.firebase.FirebaseApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Decides which backend the app talks to. When `app/google-services.json` is present at build
 * time Firebase initialises automatically and the live backend is used; otherwise the app runs
 * in self-contained demo mode so it can still be built, installed and explored.
 */
@Singleton
class BackendConfig @Inject constructor(@ApplicationContext context: Context) {
    val isFirebaseEnabled: Boolean = FirebaseApp.getApps(context).isNotEmpty()
    val isDemoMode: Boolean get() = !isFirebaseEnabled
}

/** Long-lived scope for app-wide flows (auth state, settings). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Error with a message that is safe and friendly to show in the UI. */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
