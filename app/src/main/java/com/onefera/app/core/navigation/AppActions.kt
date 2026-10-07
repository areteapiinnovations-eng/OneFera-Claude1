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
    /** Picks a photo and opens the story editor. */
    val createStory: () -> Unit = {},
    val openFollowList: (uid: String, followers: Boolean) -> Unit = { _, _ -> },
    val openNotifications: () -> Unit = {},
    val createPost: (reel: Boolean) -> Unit = {},
    val openChat: (conversationId: String) -> Unit = {},
    val newChat: () -> Unit = {},
    val openProduct: (productId: String) -> Unit = {},
    val openCart: () -> Unit = {},
    val checkout: () -> Unit = {},
    val openOrders: () -> Unit = {},
    val openOrder: (orderId: String) -> Unit = {},
    val openWishlist: () -> Unit = {},
    val openSellerHub: () -> Unit = {},
    /** Seller registration, or its status once submitted. */
    val becomeSeller: () -> Unit = {},
    /** Opens the listing editor; an empty id creates a new listing. */
    val editListing: (productId: String) -> Unit = {},
    val openSellerOrder: (orderId: String) -> Unit = {},
    val openRewards: () -> Unit = {},
    val openLeaderboard: () -> Unit = {},
    val openMembership: () -> Unit = {},
    val openStore: (sellerId: String) -> Unit = {},
    val openBlockedAccounts: () -> Unit = {},
    val back: () -> Unit = {},
)

val LocalAppActions = staticCompositionLocalOf { AppActions() }
