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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.core.navigation.AppActions
import com.onefera.app.data.push.DeepLinks
import com.onefera.app.feature.chat.ChatScreen
import com.onefera.app.feature.chat.NewChatScreen
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
import com.onefera.app.feature.shop.CartScreen
import com.onefera.app.feature.shop.CheckoutScreen
import com.onefera.app.feature.shop.OrderScreen
import com.onefera.app.feature.shop.OrdersScreen
import com.onefera.app.feature.shop.ProductScreen
import com.onefera.app.feature.shop.WishlistScreen

@Composable
fun OneFeraNavHost(
    startDestination: Any,
    isSignedIn: Boolean,
    isDemoMode: Boolean,
    deepLinks: DeepLinks? = null,
    navController: NavHostController = rememberNavController(),
) {
    // Open notification targets once the user is signed in and the main screen exists.
    val pendingLink = deepLinks?.pending?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(pendingLink, isSignedIn) {
        val link = pendingLink ?: return@LaunchedEffect
        if (!isSignedIn) return@LaunchedEffect
        val (kind, value) = link.substringBefore(':') to link.substringAfter(':', "")
        when (kind) {
            "chat" -> if (value.isNotEmpty()) navController.navigate(ChatRoute(value))
            "post" -> if (value.isNotEmpty()) navController.navigate(PostDetailRoute(value))
            "user" -> if (value.isNotEmpty()) navController.navigate(UserProfileRoute(value))
            "notifications" -> navController.navigate(NotificationsRoute)
            "order" -> if (value.isNotEmpty()) navController.navigate(OrderRoute(value))
            "product" -> if (value.isNotEmpty()) navController.navigate(ProductRoute(value))
        }
        deepLinks?.consumed()
    }

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
            openChat = { id -> navController.navigate(ChatRoute(id)) },
            newChat = { navController.navigate(NewChatRoute) },
            openProduct = { navController.navigate(ProductRoute(it)) },
            openCart = { navController.navigate(CartRoute) { launchSingleTop = true } },
            checkout = { navController.navigate(CheckoutRoute) { launchSingleTop = true } },
            openOrders = { navController.navigate(OrdersRoute) { launchSingleTop = true } },
            openOrder = { navController.navigate(OrderRoute(it)) },
            openWishlist = { navController.navigate(WishlistRoute) { launchSingleTop = true } },
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
        composable<ChatRoute> { ChatScreen(onBack = { navController.popBackStack() }) }
        composable<ProductRoute> { ProductScreen(onBack = { navController.popBackStack() }) }
        composable<CartRoute> { CartScreen(onBack = { navController.popBackStack() }) }
        composable<CheckoutRoute> {
            CheckoutScreen(
                onBack = { navController.popBackStack() },
                onPlaced = { orderId ->
                    // Leave checkout and cart behind: back from the confirmation returns to where shopping started.
                    navController.navigate(OrderRoute(orderId, justPlaced = true)) { popUpTo<CartRoute> { inclusive = true } }
                },
            )
        }
        composable<OrdersRoute> { OrdersScreen(onBack = { navController.popBackStack() }) }
        composable<OrderRoute> { OrderScreen(onBack = { navController.popBackStack() }) }
        composable<WishlistRoute> { WishlistScreen(onBack = { navController.popBackStack() }) }
        composable<NewChatRoute> {
            NewChatScreen(
                onBack = { navController.popBackStack() },
                onOpened = { id ->
                    navController.navigate(ChatRoute(id)) { popUpTo<NewChatRoute> { inclusive = true } }
                },
            )
        }
    }
    }
}

private fun NavHostController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
