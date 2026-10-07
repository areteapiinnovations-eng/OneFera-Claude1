package com.onefera.app.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
@Serializable data object OnboardingRoute
@Serializable data object SignInRoute
@Serializable data object SignUpRoute
@Serializable data object ForgotPasswordRoute
@Serializable data object MainRoute
@Serializable data object SettingsRoute
@Serializable data object EditProfileRoute
@Serializable data object NotificationsRoute
@Serializable data class CreatePostRoute(val reel: Boolean = false)
@Serializable data class StoryViewerRoute(val authorUid: String)
@Serializable data object StoryEditorRoute
@Serializable data class UserProfileRoute(val uid: String)
@Serializable data class FollowListRoute(val uid: String, val followers: Boolean)
@Serializable data class PostDetailRoute(val postId: String)
@Serializable data class TagRoute(val tag: String)
@Serializable data class ChatRoute(val conversationId: String)
@Serializable data object NewChatRoute
@Serializable data class ProductRoute(val productId: String)
@Serializable data object CartRoute
@Serializable data object CheckoutRoute
@Serializable data object OrdersRoute
@Serializable data class OrderRoute(val orderId: String, val justPlaced: Boolean = false)
@Serializable data object WishlistRoute
@Serializable data object SellerHubRoute
@Serializable data object SellerRegistrationRoute
@Serializable data class ListingEditorRoute(val productId: String = "")
@Serializable data class SellerOrderRoute(val orderId: String)
@Serializable data object RewardsRoute
@Serializable data object LeaderboardRoute
@Serializable data object MembershipRoute
@Serializable data class StoreRoute(val sellerId: String)
@Serializable data object BlockedAccountsRoute
