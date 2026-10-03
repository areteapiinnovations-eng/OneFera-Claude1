package com.onefera.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.onefera.app.feature.auth.ForgotPasswordScreen
import com.onefera.app.feature.auth.SignInScreen
import com.onefera.app.feature.auth.SignUpScreen
import com.onefera.app.feature.main.MainScreen
import com.onefera.app.feature.onboarding.OnboardingScreen
import com.onefera.app.feature.profile.EditProfileScreen
import com.onefera.app.feature.settings.SettingsScreen

@Composable
fun OneFeraNavHost(
    startDestination: Any,
    isSignedIn: Boolean,
    isDemoMode: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    // Signing out from anywhere in the app returns to Sign in with a fresh back stack.
    LaunchedEffect(isSignedIn) {
        if (!isSignedIn) {
            val destination = navController.currentDestination ?: return@LaunchedEffect
            val inAuthFlow = destination.hasRoute<SignInRoute>() || destination.hasRoute<SignUpRoute>() ||
                destination.hasRoute<ForgotPasswordRoute>() || destination.hasRoute<OnboardingRoute>()
            if (!inAuthFlow) navController.navigateClearingBackStack(SignInRoute)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(250)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(280)) },
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onSignIn = { navController.navigateClearingBackStack(SignInRoute) },
                onCreateAccount = {
                    navController.navigateClearingBackStack(SignInRoute)
                    navController.navigate(SignUpRoute)
                },
            )
        }
        composable<SignInRoute> {
            SignInScreen(
                isDemoMode = isDemoMode,
                onSignedIn = { navController.navigateClearingBackStack(MainRoute) },
                onCreateAccount = { navController.navigate(SignUpRoute) },
                onForgotPassword = { navController.navigate(ForgotPasswordRoute) },
            )
        }
        composable<SignUpRoute> {
            SignUpScreen(
                isDemoMode = isDemoMode,
                onSignedUp = { navController.navigateClearingBackStack(MainRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<ForgotPasswordRoute> {
            ForgotPasswordScreen(onBack = { navController.popBackStack() })
        }
        composable<MainRoute> {
            MainScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onEditProfile = { navController.navigate(EditProfileRoute) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<EditProfileRoute> {
            EditProfileScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
