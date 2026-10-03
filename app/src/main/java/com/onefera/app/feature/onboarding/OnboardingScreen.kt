package com.onefera.app.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraMark
import com.onefera.app.core.designsystem.component.OneFeraTagline
import com.onefera.app.core.designsystem.component.OneFeraWordmark
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.component.rememberReducedMotion
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    fun finish(then: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingSeen()
            then()
        }
    }
}

private data class OnboardingPage(
    val kicker: String,
    val title: String,
    val body: String,
    @DrawableRes val icon: Int,
    val orbit: List<String>,
)

private val pages = listOf(
    OnboardingPage(
        kicker = "SOCIAL",
        title = "Connect your vibe",
        body = "Drop moments and reels, find your circle and keep your streak glowing.",
        icon = R.drawable.ic_chat_filled,
        orbit = listOf("✨", "💬", "🎧"),
    ),
    OnboardingPage(
        kicker = "SHOP",
        title = "Shop the drop",
        body = "Tap any tagged product in a post or reel. Cart it in a second, flex it forever.",
        icon = R.drawable.ic_shop_filled,
        orbit = listOf("👟", "🛍️", "⚡"),
    ),
    OnboardingPage(
        kicker = "SELL",
        title = "Sell & glow up",
        body = "Flip to seller mode, list in minutes and stack Aura with every order.",
        icon = R.drawable.ic_bolt_filled,
        orbit = listOf("📦", "💸", "🚀"),
    ),
)

@Composable
fun OnboardingScreen(
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.lastIndex

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OneFeraWordmark(height = 26.dp)
                Spacer(Modifier.weight(1f))
                if (!isLast) {
                    TextButton(onClick = { viewModel.finish(onSignIn) }) {
                        Text("Skip", color = OneFeraTheme.extras.muted)
                    }
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                OnboardingPageContent(pages[index])
            }

            PagerDots(count = pages.size, current = pagerState.currentPage)
            Spacer(Modifier.height(24.dp))

            if (isLast) {
                GradientButton(text = "Create my account", onClick = { viewModel.finish(onCreateAccount) })
                Spacer(Modifier.height(12.dp))
                GlassButton(text = "I already have an account", onClick = { viewModel.finish(onSignIn) }, modifier = Modifier.fillMaxWidth())
            } else {
                GradientButton(
                    text = "Next",
                    trailingIcon = R.drawable.ic_chevron_right,
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    OneFeraTagline()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    val extras = OneFeraTheme.extras
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HeroOrb(page)
        Spacer(Modifier.height(40.dp))
        Text(
            page.kicker,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.gradientTint(extras.horizontalGradient()),
        )
        Spacer(Modifier.height(8.dp))
        Text(page.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = extras.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** Glowing orb with the brand mark in the middle and emoji "bubbles" orbiting it. */
@Composable
private fun HeroOrb(page: OnboardingPage) {
    val extras = OneFeraTheme.extras
    val reducedMotion = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else 360f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    val bob by transition.animateFloat(
        initialValue = -6f,
        targetValue = if (reducedMotion) -6f else 6f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(220.dp)
                .rotate(spin)
                .border(1.5.dp, extras.gradientBrush(), CircleShape),
        ) {
            page.orbit.forEachIndexed { i, emoji ->
                val angle = Math.toRadians((i * 120.0) - 90.0)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = (110 * kotlin.math.cos(angle)).dp, y = (110 * kotlin.math.sin(angle)).dp)
                        .rotate(-spin)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, extras.glassBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
            }
        }
        Box(
            Modifier
                .offset(y = bob.dp)
                .size(140.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, extras.glassBorder, RoundedCornerShape(40.dp)),
            contentAlignment = Alignment.Center,
        ) {
            OneFeraMark(Modifier.width(92.dp))
            Icon(
                painterResource(page.icon),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(extras.gradientBrush())
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    val extras = OneFeraTheme.extras
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 28.dp else 8.dp, label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .then(
                        if (index == current) Modifier.background(extras.horizontalGradient())
                        else Modifier.background(extras.glassBorder),
                    ),
            )
        }
    }
}
