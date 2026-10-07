package com.onefera.app.data.update

/**
 * Release control, stored in Firestore at `config/app` and edited in the Firebase console:
 * - `minVersionCode`: builds below this must update before they can be used (e.g. a broken release).
 * - `latestVersionCode`: the newest build; older ones get a dismissible "update available" prompt.
 * - `message`: optional text shown on the update screen.
 * - `updateUrl`: where to update when Google Play can't do it in-app (testers installed from an
 *   APK or Firebase App Distribution). Defaults to the Play Store listing.
 */
data class UpdatePolicy(
    val minVersionCode: Int = 0,
    val latestVersionCode: Int = 0,
    val message: String = "",
    val updateUrl: String = "",
)

/** What the app should show about updates right now. */
sealed interface UpdateState {
    data object None : UpdateState
    /** A newer version exists; the user may update now or later. */
    data class Available(val message: String) : UpdateState
    /** This build is no longer supported; nothing else is shown until the user updates. */
    data class Required(val message: String) : UpdateState
}

object UpdateRules {
    const val DEFAULT_REQUIRED = "This version of OneFera is no longer supported. Update to keep going, it only takes a moment."
    const val DEFAULT_AVAILABLE = "A new version of OneFera is ready with fixes and new features."

    /**
     * Combines the server policy with what Google Play reports ([playVersionCode] is the version
     * Play can install, or 0 when Play has nothing or the app wasn't installed from Play).
     */
    fun evaluate(currentVersionCode: Int, policy: UpdatePolicy?, playVersionCode: Int = 0, dismissedVersionCode: Int = 0): UpdateState {
        val message = policy?.message?.takeIf { it.isNotBlank() }
        if (policy != null && currentVersionCode < policy.minVersionCode) {
            return UpdateState.Required(message ?: DEFAULT_REQUIRED)
        }
        val newest = maxOf(policy?.latestVersionCode ?: 0, playVersionCode)
        return if (newest > currentVersionCode && newest > dismissedVersionCode) {
            UpdateState.Available(message ?: DEFAULT_AVAILABLE)
        } else {
            UpdateState.None
        }
    }

    fun storeUrl(packageName: String) = "https://play.google.com/store/apps/details?id=$packageName"
}
