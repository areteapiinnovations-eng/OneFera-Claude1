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
