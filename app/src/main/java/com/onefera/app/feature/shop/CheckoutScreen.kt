package com.onefera.app.feature.shop

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.data.model.Address
import com.onefera.app.data.model.CartItem
import com.onefera.app.data.model.CartTotals
import com.onefera.app.data.model.PaymentMethod
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.shop.CheckoutSession
import com.onefera.app.data.shop.PaymentResult
import com.onefera.app.data.shop.ShopRepository
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.payments.RazorpayBridge
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.Coupon
import com.onefera.app.data.rewards.RewardsRepository
import com.onefera.app.data.user.UserRepository
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CheckoutForm(val address: Address = Address(), val method: PaymentMethod = PaymentMethod.Upi, val showErrors: Boolean = false)

data class CheckoutUiState(
    val items: List<CartItem> = emptyList(),
    val form: CheckoutForm = CheckoutForm(),
    val busy: Boolean = false,
    /** Non-null while the payment sheet is open. */
    val session: CheckoutSession? = null,
    val coupons: List<Coupon> = emptyList(),
    val couponId: String? = null,
    /** OneFera+ perk. */
    val freeDelivery: Boolean = false,
) {
    val coupon: Coupon? get() = coupons.firstOrNull { it.id == couponId }
    val totals: CartTotals get() = CartTotals.of(items, coupon, freeDelivery)
}

sealed interface CheckoutEvent {
    data class Message(val text: String) : CheckoutEvent
    data class Placed(val orderId: String) : CheckoutEvent
    /** Live payments: the screen hands the session to Razorpay Checkout (needs the Activity). */
    data object PayLive : CheckoutEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val shop: ShopRepository,
    rewards: RewardsRepository,
    private val auth: AuthRepository,
    users: UserRepository,
    private val razorpay: RazorpayBridge,
) : ViewModel() {
    private var liveSession: CheckoutSession? = null
    private val form = MutableStateFlow(CheckoutForm())
    private val busy = MutableStateFlow(false)
    private val session = MutableStateFlow<CheckoutSession?>(null)
    private val couponId = MutableStateFlow<String?>(null)
    private val plus = auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(null) else users.observeProfile(uid) }
        .map { it?.membership?.active()?.hasPlusPerks == true }
    private val _events = Channel<CheckoutEvent>(Channel.BUFFERED)
    val events: Flow<CheckoutEvent> = _events.receiveAsFlow()

    val state: StateFlow<CheckoutUiState> = combine(
        combine(shop.cart(), form, busy, session) { items, f, b, s -> CheckoutUiState(items, f, b, s) },
        rewards.coupons(),
        couponId,
        plus,
    ) { base, coupons, selected, isPlus -> base.copy(coupons = coupons, couponId = selected, freeDelivery = isPlus) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckoutUiState())

    fun toggleCoupon(id: String) = couponId.update { if (it == id) null else id }

    init {
        // Pre-fill the last used address.
        viewModelScope.launch {
            shop.savedAddress().first()?.let { saved -> form.update { if (it.address == Address()) it.copy(address = saved) else it } }
        }
    }

    fun onAddress(address: Address) = form.update { it.copy(address = address) }
    fun onMethod(method: PaymentMethod) = form.update { it.copy(method = method) }

    fun placeOrder() {
        val f = form.value
        if (!f.address.isComplete) {
            form.update { it.copy(showErrors = true) }
            viewModelScope.launch { _events.send(CheckoutEvent.Message("Please complete your delivery address.")) }
            return
        }
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            shop.startCheckout(f.address, f.method, state.value.coupon?.id)
                .onSuccess { s ->
                    if (f.method == PaymentMethod.Cod) {
                        confirm(s, PaymentResult("cod_" + UUID.randomUUID().toString().take(8)))
                    } else if (!s.simulated) {
                        liveSession = s
                        _events.send(CheckoutEvent.PayLive)
                    } else {
                        busy.value = false
                        session.value = s
                    }
                }
                .onFailure {
                    busy.value = false
                    _events.send(CheckoutEvent.Message(it.message ?: "Checkout failed. Please try again."))
                }
        }
    }

    /** Opens Razorpay Checkout for the pending live session, then confirms the result server-side. */
    fun payLive(activity: Activity) {
        val s = liveSession ?: return
        liveSession = null
        val email = (auth.session.value as? SessionState.SignedIn)?.email
        viewModelScope.launch {
            razorpay.pay(activity, s, form.value.address, email)
                .onSuccess { confirm(s, it) }
                .onFailure {
                    busy.value = false
                    _events.send(CheckoutEvent.Message(it.message ?: "Payment didn't go through."))
                }
        }
    }

    /** Called by the payment sheet. */
    fun onPaid(result: PaymentResult) {
        val s = session.value ?: return
        session.value = null
        busy.value = true
        viewModelScope.launch { confirm(s, result) }
    }

    fun onPaymentFailed(reason: String) {
        session.value = null
        viewModelScope.launch { _events.send(CheckoutEvent.Message(reason)) }
    }

    private suspend fun confirm(s: CheckoutSession, result: PaymentResult) {
        shop.confirmPayment(s, result)
            .onSuccess { _events.send(CheckoutEvent.Placed(it.id)) }
            .onFailure { _events.send(CheckoutEvent.Message(it.message ?: "We couldn't confirm your payment.")) }
        busy.value = false
    }
}

@Composable
fun CheckoutScreen(onBack: () -> Unit, onPlaced: (String) -> Unit, viewModel: CheckoutViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CheckoutEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text)
                }
                is CheckoutEvent.Placed -> onPlaced(event.orderId)
                CheckoutEvent.PayLive -> context.findActivity()?.let(viewModel::payLive)
            }
        }
    }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { ScreenHeader("Checkout", onBack = onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(formatRupees(state.totals.total), style = MaterialTheme.typography.titleLarge)
                        Text("${state.items.sumOf { it.quantity }} items", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                    }
                    GradientButton(
                        if (state.form.method == PaymentMethod.Cod) "Place order" else "Pay securely",
                        onClick = viewModel::placeOrder,
                        enabled = state.items.isNotEmpty(),
                        loading = state.busy,
                        modifier = Modifier.weight(1.3f),
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AddressForm(state.form.address, state.form.showErrors, viewModel::onAddress)
                PaymentMethods(state.form.method, viewModel::onMethod)
                if (state.coupons.isNotEmpty() || state.freeDelivery) {
                    CouponPicker(state, onToggle = viewModel::toggleCoupon)
                }
                TotalsCard(state.totals)
                Text(
                    "Payments are processed by Razorpay. OneFera never sees your card or UPI PIN.",
                    style = MaterialTheme.typography.labelSmall,
                    color = OneFeraTheme.extras.muted,
                )
            }
        }

        AnimatedVisibility(
            visible = state.session != null,
            enter = fadeIn() + slideInVertically { it / 4 },
            exit = fadeOut() + slideOutVertically { it / 4 },
        ) {
            val s = state.session
            if (s != null) {
                SimulatedPaymentSheet(
                    session = s,
                    onPaid = viewModel::onPaid,
                    onFailed = viewModel::onPaymentFailed,
                )
            }
        }
    }
}

@Composable
private fun AddressForm(address: Address, showErrors: Boolean, onChange: (Address) -> Unit) {
    fun err(bad: Boolean, text: String) = if (showErrors && bad) text else null
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_location), contentDescription = null, modifier = Modifier.size(20.dp).gradientTint(OneFeraTheme.extras.gradientBrush()))
            Spacer(Modifier.width(8.dp))
            Text("Delivery address", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OneFeraTextField(address.name, { onChange(address.copy(name = it.take(60))) }, "Full name", error = err(address.name.isBlank(), "Enter your name"))
            OneFeraTextField(
                address.phone,
                { v -> onChange(address.copy(phone = v.filter(Char::isDigit).take(10))) },
                "Mobile number",
                keyboardType = KeyboardType.Phone,
                error = err(address.phone.length != 10, "Enter a 10-digit mobile number"),
            )
            OneFeraTextField(address.line1, { onChange(address.copy(line1 = it.take(120))) }, "House / flat, street", error = err(address.line1.isBlank(), "Enter your address"))
            OneFeraTextField(address.line2, { onChange(address.copy(line2 = it.take(120))) }, "Area, landmark (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OneFeraTextField(address.city, { onChange(address.copy(city = it.take(40))) }, "City", modifier = Modifier.weight(1f), error = err(address.city.isBlank(), "Required"))
                OneFeraTextField(
                    address.pincode,
                    { v -> onChange(address.copy(pincode = v.filter(Char::isDigit).take(6))) },
                    "PIN code",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                    error = err(address.pincode.length != 6, "6 digits"),
                )
            }
            OneFeraTextField(address.state, { onChange(address.copy(state = it.take(40))) }, "State", error = err(address.state.isBlank(), "Enter your state"))
        }
    }
}

@Composable
private fun PaymentMethods(selected: PaymentMethod, onSelect: (PaymentMethod) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Pay with", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        PaymentMethod.entries.forEach { method ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.RadioButton) { onSelect(method) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = method == selected, onClick = { onSelect(method) })
                Icon(painterResource(method.icon), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(method.label, style = MaterialTheme.typography.bodyLarge)
                    Text(method.hint, style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                }
            }
        }
    }
}

@Composable
private fun CouponPicker(state: CheckoutUiState, onToggle: (String) -> Unit) {
    val extras = OneFeraTheme.extras
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sell), contentDescription = null, modifier = Modifier.size(20.dp).gradientTint(extras.gradientBrush()))
            Spacer(Modifier.width(8.dp))
            Text("Rewards", style = MaterialTheme.typography.titleMedium)
        }
        if (state.freeDelivery) {
            Spacer(Modifier.height(6.dp))
            Text("OneFera+ · free delivery applied 💎", style = MaterialTheme.typography.labelLarge, color = StatusColors.Success)
        }
        val subtotal = state.totals.subtotal
        state.coupons.forEach { coupon ->
            val eligible = subtotal >= coupon.minOrder
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = eligible, role = Role.Checkbox) { onToggle(coupon.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = coupon.id == state.couponId, onCheckedChange = null, enabled = eligible)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(coupon.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (eligible) coupon.condition else "Add ${formatRupees(coupon.minOrder - subtotal)} more to use",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.muted,
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val PaymentMethod.icon: Int
    get() = when (this) {
        PaymentMethod.Upi -> R.drawable.ic_bolt
        PaymentMethod.Card -> R.drawable.ic_credit_card
        PaymentMethod.NetBanking -> R.drawable.ic_bank
        PaymentMethod.Cod -> R.drawable.ic_payments
    }

private val PaymentMethod.hint: String
    get() = when (this) {
        PaymentMethod.Upi -> "GPay, PhonePe, Paytm or any UPI app"
        PaymentMethod.Card -> "Credit or debit card"
        PaymentMethod.NetBanking -> "All major Indian banks"
        PaymentMethod.Cod -> "Pay when it arrives"
    }

/**
 * Stand-in for the Razorpay checkout while payments run in simulated mode (the default until
 * live keys are configured, see README). It returns a payment id exactly like the real SDK.
 */
@Composable
private fun SimulatedPaymentSheet(session: CheckoutSession, onPaid: (PaymentResult) -> Unit, onFailed: (String) -> Unit) {
    val extras = OneFeraTheme.extras
    var processing by remember { mutableStateOf(false) }
    BackHandler(enabled = !processing) { onFailed("Payment cancelled. Your cart is still saved.") }
    LaunchedEffect(processing) {
        if (processing) {
            kotlinx.coroutines.delay(1_400)
            onPaid(PaymentResult(paymentId = "pay_sim_" + UUID.randomUUID().toString().replace("-", "").take(14)))
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(extras.gradientBrush()), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = extras.onGradient, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Razorpay secure checkout", style = MaterialTheme.typography.titleMedium)
                    Text("Simulated · no real money moves", style = MaterialTheme.typography.labelSmall, color = StatusColors.Warning)
                }
            }
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(extras.glass).border(1.dp, extras.glassBorder, shape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("OneFera · Order ${session.orderId}", style = MaterialTheme.typography.labelMedium, color = extras.muted)
                Text(formatRupees(session.amount), style = MaterialTheme.typography.headlineMedium)
                Text("via ${session.method.label}", style = MaterialTheme.typography.bodyMedium)
            }
            if (processing) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Processing payment…", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                GradientButton("Pay ${formatRupees(session.amount)}", onClick = { processing = true })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Cancel", onClick = { onFailed("Payment cancelled. Your cart is still saved.") }, modifier = Modifier.weight(1f), height = 48.dp)
                    GlassButton("Simulate failure", onClick = { onFailed("Payment failed (simulated). No money was taken, try again.") }, modifier = Modifier.weight(1f), height = 48.dp)
                }
            }
        }
    }
}
