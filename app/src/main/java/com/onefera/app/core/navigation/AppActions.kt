package com.onefera.app.core.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-wide navigation callbacks, provided once by the NavHost so deeply nested composables
 * (post cards, notification rows, search results…) can open other screens without
 * threading callbacks through every layer.
 */
data class AppActions(
    val openUser: (uid: String) -> Unit = {},
    val openPost: (postId: String) -> Unit = {},
    val openTag: (tag: String) -> Unit = {},
    val openStories: (authorUid: String) -> Unit = {},
    val openFollowList: (uid: String, followers: Boolean) -> Unit = { _, _ -> },
    val openNotifications: () -> Unit = {},
    val createPost: (reel: Boolean) -> Unit = {},
    val back: () -> Unit = {},
)

val LocalAppActions = staticCompositionLocalOf { AppActions() }
