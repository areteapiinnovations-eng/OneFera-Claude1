package com.onefera.app.feature.rewards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.MembershipPlan
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.rewards.RewardsRepository
import com.onefera.app.data.payments.PlayBilling
import com.android.billingclient.api.ProductDetails
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MembershipViewModel @Inject constructor(
    auth: AuthRepository,
    users: UserRepository,
    private val rewards: RewardsRepository,
    private val billing: PlayBilling,
) : ViewModel() {
    /** Play Store listings for the plans, when Play Billing is set up for this build. */
    var playProducts by mutableStateOf<Map<MembershipPlan, ProductDetails>>(emptyMap())
        private set

    init {
        viewModelScope.launch {
            playProducts = runCatching { billing.products() }.getOrDefault(emptyMap())
            runCatching { billing.syncPurchases() }
        }
    }

    fun priceLabel(plan: MembershipPlan): String? =
        playProducts[plan]?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val profile: StateFlow<UserProfile?> = auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(null) else users.observeProfile(uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var busy by mutableStateOf(false)
        private set

    /** Google Play when the plan is listed there; otherwise the simulated purchase (demo / test builds). */
    fun subscribe(plan: MembershipPlan, activity: Activity?) {
        val details = playProducts[plan]
        if (details != null && activity != null) {
            perform({ billing.purchase(activity, plan, details) }, "Welcome to ${plan.label} 💎")
        } else {
            perform({ rewards.subscribe(plan) }, "Welcome to ${plan.label} 💎")
        }
    }
    fun cancel() = perform({ rewards.cancelMembership() }, "Membership cancelled. You can rejoin anytime.")

    private fun perform(action: suspend () -> Result<Unit>, success: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            action()
                .onSuccess { _messages.send(success) }
                .onFailure { _messages.send(it.message ?: "Something went wrong.") }
            busy = false
        }
    }
}

@Composable
fun MembershipScreen(onBack: () -> Unit, viewModel: MembershipViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by rememberSaveable { mutableStateOf<MembershipPlan?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val active = profile?.membership?.active() ?: MembershipPlan.None
    val date = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val context = LocalContext.current

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.55f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { ScreenHeader("Membership 💎", onBack = onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (active != MembershipPlan.None) {
                    Text(
                        "You're on ${active.label} until ${date.format(Date(profile?.membershipExpiresAt ?: 0L))}",
                        style = MaterialTheme.typography.titleMedium,
                        color = StatusColors.Success,
                    )
                }
                listOf(MembershipPlan.Plus, MembershipPlan.SellerPro).forEach { plan ->
                    PlanCard(
                        plan = plan,
                        price = viewModel.priceLabel(plan) ?: formatRupees(plan.monthlyPrice),
                        current = plan == active,
                        busy = viewModel.busy,
                        onChoose = { confirm = plan },
                    )
                }
                if (active != MembershipPlan.None) {
                    GlassButton("Cancel membership", onClick = viewModel::cancel, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    if (viewModel.playProducts.isNotEmpty()) {
                        "Billed monthly through Google Play. Manage or cancel anytime in Play Store → Subscriptions."
                    } else {
                        "Billed monthly. Cancel anytime. In this build, purchases are simulated; Play Store releases charge through Google Play."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = OneFeraTheme.extras.muted,
                )
            }
        }
    }

    confirm?.let { plan ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Get ${plan.label}?") },
            text = {
                Text(
                    if (viewModel.playProducts.containsKey(plan)) {
                        "${viewModel.priceLabel(plan)} a month, billed by Google Play."
                    } else {
                        "${formatRupees(plan.monthlyPrice)} a month. This is a simulated purchase, no money is charged."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.subscribe(plan, context.findActivity()) }) { Text("Subscribe") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Not now") } },
        )
    }
}

@Composable
private fun PlanCard(plan: MembershipPlan, price: String, current: Boolean, busy: Boolean, onChoose: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .border(if (current) 2.dp else 1.dp, if (current) extras.gradientBrush() else androidx.compose.ui.graphics.SolidColor(extras.glassBorder), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (plan == MembershipPlan.SellerPro) R.drawable.ic_storefront else R.drawable.ic_crown_filled), contentDescription = null, modifier = Modifier.size(26.dp).gradientTint(extras.gradientBrush()))
            Spacer(Modifier.width(10.dp))
            Text(plan.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (current) GradientTag("ACTIVE")
        }
        Text(plan.tagline, style = MaterialTheme.typography.bodyMedium, color = extras.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(price, style = MaterialTheme.typography.headlineMedium)
            Text(" / month", style = MaterialTheme.typography.bodyMedium, color = extras.muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        plan.benefits.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = StatusColors.Success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(b, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        GradientButton(if (current) "Renew for a month" else "Get ${plan.label}", onClick = onChoose, enabled = !busy)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
