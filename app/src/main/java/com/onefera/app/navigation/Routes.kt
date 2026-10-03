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
@Serializable data class UserProfileRoute(val uid: String)
@Serializable data class FollowListRoute(val uid: String, val followers: Boolean)
@Serializable data class PostDetailRoute(val postId: String)
@Serializable data class TagRoute(val tag: String)
