package com.onefera.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.onefera.app.core.navigation.AppActions
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.feature.auth.ForgotPasswordScreen
import com.onefera.app.feature.create.CreatePostScreen
import com.onefera.app.feature.notifications.NotificationsScreen
import com.onefera.app.feature.post.PostDetailScreen
import com.onefera.app.feature.search.TagScreen
import com.onefera.app.feature.story.StoryViewerScreen
import com.onefera.app.feature.user.FollowListScreen
import com.onefera.app.feature.user.UserProfileScreen
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

    val appActions = remember(navController) {
        AppActions(
            openUser = { navController.navigate(UserProfileRoute(it)) },
            openPost = { navController.navigate(PostDetailRoute(it)) },
            openTag = { navController.navigate(TagRoute(it)) },
            openStories = { navController.navigate(StoryViewerRoute(it)) },
            openFollowList = { uid, followers -> navController.navigate(FollowListRoute(uid, followers)) },
            openNotifications = { navController.navigate(NotificationsRoute) { launchSingleTop = true } },
            createPost = { reel -> navController.navigate(CreatePostRoute(reel)) },
            back = { navController.popBackStack() },
        )
    }

    CompositionLocalProvider(LocalAppActions provides appActions) {
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
        composable<CreatePostRoute>(
            enterTransition = { slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(320)) },
            popExitTransition = { slideOutVertically(tween(280)) { it / 3 } + fadeOut(tween(280)) },
        ) {
            CreatePostScreen(onBack = { navController.popBackStack() }, onPublished = { navController.popBackStack() })
        }
        composable<StoryViewerRoute>(
            enterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(200)) },
        ) {
            StoryViewerScreen(onClose = { navController.popBackStack() })
        }
        composable<UserProfileRoute> { UserProfileScreen(onBack = { navController.popBackStack() }) }
        composable<FollowListRoute> { FollowListScreen(onBack = { navController.popBackStack() }) }
        composable<PostDetailRoute> { PostDetailScreen(onBack = { navController.popBackStack() }) }
        composable<TagRoute> { TagScreen(onBack = { navController.popBackStack() }) }
        composable<NotificationsRoute> { NotificationsScreen(onBack = { navController.popBackStack() }) }
    }
    }
}

private fun NavHostController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
