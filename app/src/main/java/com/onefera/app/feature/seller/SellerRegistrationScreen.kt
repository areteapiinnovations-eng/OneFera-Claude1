package com.onefera.app.feature.seller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.PayoutMethod
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.model.SellerApplication
import com.onefera.app.data.model.SellerBusinessType
import com.onefera.app.data.model.SellerStatus
import com.onefera.app.data.model.SellerValidation
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.seller.SellerOnboardingRepository
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SellerRegistrationUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val application: SellerApplication? = null,
    val submitting: Boolean = false,
    val switching: Boolean = false,
    val error: String? = null,
    /** True while the user is filling in (or correcting) the form. */
    val editing: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SellerRegistrationViewModel @Inject constructor(
    auth: AuthRepository,
    private val users: UserRepository,
    private val onboarding: SellerOnboardingRepository,
) : ViewModel() {
    private val form = MutableStateFlow(SellerRegistrationUiState())

    val state: StateFlow<SellerRegistrationUiState> = combine(
        auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(null) else users.observeProfile(uid) },
        onboarding.application(),
        form,
    ) { profile, application, f -> f.copy(loading = false, profile = profile, application = application) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SellerRegistrationUiState())

    fun startEditing() = form.update { it.copy(editing = true, error = null) }

    fun submit(application: SellerApplication) {
        if (form.value.submitting) return
        form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            onboarding.submit(application)
                .onSuccess { form.update { it.copy(submitting = false, editing = false) } }
                .onFailure { e -> form.update { it.copy(submitting = false, error = e.message ?: "Couldn't submit. Try again.") } }
        }
    }

    fun setMode(mode: AccountMode) {
        val uid = state.value.profile?.uid ?: return
        form.update { it.copy(switching = true, error = null) }
        viewModelScope.launch {
            users.setAccountMode(uid, mode)
                .onSuccess { form.update { it.copy(switching = false) } }
                .onFailure { e -> form.update { it.copy(switching = false, error = e.message) } }
        }
    }
}

/**
 * Become a Seller: personal account → registration form → submit → status (pending, approved or
 * rejected with a reason) → Seller mode. Nobody becomes a seller just by tapping the button.
 */
@Composable
fun SellerRegistrationScreen(onBack: () -> Unit, viewModel: SellerRegistrationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ScreenHeader("Become a Seller", onBack = onBack)
            val profile = state.profile
            val application = state.application
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                profile == null -> Text("Finish setting up your profile first.", modifier = Modifier.padding(16.dp))
                profile.isMinor -> StatusCard("🔞", "Sellers must be 18+", "Selling on OneFera is open to members aged 18 and over.")
                state.editing || (application == null && profile.accountMode != AccountMode.Seller) ->
                    if (application == null && !state.editing) Intro(onStart = viewModel::startEditing)
                    else RegistrationForm(profile, application, state.submitting, state.error, onSubmit = viewModel::submit)
                else -> StatusView(profile, application, state, onEdit = viewModel::startEditing, onMode = viewModel::setMode)
            }
        }
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("🏪 Open your OneFera store", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            listOf(
                "List products and tag them in posts and reels",
                "Get orders, track them and see your sales",
                "Show up on Near for shoppers around you",
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(
                "You'll need: your PAN, a pickup address, a UPI ID or bank account for payouts, and your GSTIN if you're GST-registered.",
                style = MaterialTheme.typography.bodySmall,
                color = OneFeraTheme.extras.muted,
            )
        }
        GradientButton("Start registration", onClick = onStart, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RegistrationForm(
    profile: UserProfile,
    previous: SellerApplication?,
    submitting: Boolean,
    serverError: String?,
    onSubmit: (SellerApplication) -> Unit,
) {
    var storeName by rememberSaveable { mutableStateOf(previous?.storeName ?: "") }
    var legalName by rememberSaveable { mutableStateOf(previous?.legalName ?: profile.displayName) }
    var businessType by rememberSaveable { mutableStateOf(previous?.businessType ?: SellerBusinessType.Individual) }
    var category by rememberSaveable { mutableStateOf(previous?.category ?: ProductCategory.Fashion) }
    var description by rememberSaveable { mutableStateOf(previous?.description ?: "") }
    var phone by rememberSaveable { mutableStateOf(previous?.phone ?: "") }
    var email by rememberSaveable { mutableStateOf(previous?.email ?: profile.email) }
    var addressLine by rememberSaveable { mutableStateOf(previous?.addressLine ?: "") }
    var city by rememberSaveable { mutableStateOf(previous?.city ?: profile.city) }
    var stateName by rememberSaveable { mutableStateOf(previous?.state ?: "") }
    var pincode by rememberSaveable { mutableStateOf(previous?.pincode ?: "") }
    var pan by rememberSaveable { mutableStateOf(previous?.pan ?: "") }
    var gstin by rememberSaveable { mutableStateOf(previous?.gstin ?: "") }
    var payout by rememberSaveable { mutableStateOf(previous?.payoutMethod ?: PayoutMethod.Upi) }
    var upiId by rememberSaveable { mutableStateOf(previous?.upiId ?: "") }
    var accountHolder by rememberSaveable { mutableStateOf(previous?.accountHolder ?: "") }
    var accountNumber by rememberSaveable { mutableStateOf("") }
    var ifsc by rememberSaveable { mutableStateOf(previous?.ifsc ?: "") }
    var terms by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val draft = SellerApplication(
        storeName = storeName, legalName = legalName, businessType = businessType, category = category, description = description,
        phone = phone, email = email, addressLine = addressLine, city = city, state = stateName, pincode = pincode,
        gstin = gstin.uppercase(), pan = pan.uppercase(), payoutMethod = payout, upiId = upiId,
        accountHolder = accountHolder, accountNumber = accountNumber, ifsc = ifsc.uppercase(), acceptedTerms = terms,
    )
    val errors = SellerValidation.errors(draft)
    fun err(field: String) = if (showErrors) errors[field] else null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        previous?.takeIf { it.status == SellerStatus.Rejected }?.let {
            StatusBanner("Your last registration needs changes", it.rejectionReason.ifBlank { "Check your details and submit again." }, StatusColors.Warning)
        }
        Section("Store")
        OneFeraTextField(storeName, { storeName = it.take(40) }, "Store name", error = err("storeName"))
        OneFeraTextField(description, { description = it.take(300) }, "What do you sell? (optional)", singleLine = false, maxLength = 300, error = err("description"))
        Text("Main category", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductCategory.entries.forEach { c -> SelectChip("${c.emoji} ${c.label}", selected = c == category, onClick = { category = c }) }
        }

        Section("Business")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SellerBusinessType.entries.forEach { t -> SelectChip(t.label, selected = t == businessType, onClick = { businessType = t }) }
        }
        OneFeraTextField(legalName, { legalName = it.take(80) }, "Legal name (as on PAN)", error = err("legalName"))
        OneFeraTextField(pan, { pan = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(10) }, "PAN", error = err("pan"))
        OneFeraTextField(
            gstin,
            { gstin = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(15) },
            "GSTIN (if registered)",
            error = err("gstin"),
            supportingText = "Leave empty if your turnover is below the GST threshold",
        )

        Section("Contact and pickup address")
        OneFeraTextField(phone, { phone = it.filter(Char::isDigit).take(10) }, "Mobile number", keyboardType = KeyboardType.Phone, error = err("phone"))
        OneFeraTextField(email, { email = it.trim().take(120) }, "Business email", keyboardType = KeyboardType.Email, error = err("email"))
        OneFeraTextField(addressLine, { addressLine = it.take(120) }, "Address", error = err("addressLine"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OneFeraTextField(city, { city = it.take(40) }, "City", error = err("city"), modifier = Modifier.weight(1f))
            OneFeraTextField(pincode, { pincode = it.filter(Char::isDigit).take(6) }, "Pincode", keyboardType = KeyboardType.Number, error = err("pincode"), modifier = Modifier.weight(1f))
        }
        OneFeraTextField(stateName, { stateName = it.take(40) }, "State", error = err("state"))

        Section("Payouts")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PayoutMethod.entries.forEach { m -> SelectChip(m.label, selected = m == payout, onClick = { payout = m }) }
        }
        if (payout == PayoutMethod.Upi) {
            OneFeraTextField(upiId, { upiId = it.trim().take(100) }, "UPI ID (name@bank)", error = err("upiId"))
        } else {
            OneFeraTextField(accountHolder, { accountHolder = it.take(80) }, "Account holder", error = err("accountHolder"))
            OneFeraTextField(
                accountNumber,
                { accountNumber = it.filter(Char::isDigit).take(18) },
                "Account number",
                keyboardType = KeyboardType.Number,
                error = err("accountNumber"),
                supportingText = previous?.accountNumber?.takeIf { it.isNotBlank() }?.let { "On file: $it. Enter it again to keep it." },
            )
            OneFeraTextField(ifsc, { ifsc = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(11) }, "IFSC", error = err("ifsc"))
        }

        Row(Modifier.fillMaxWidth().clickable { terms = !terms }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = terms, onCheckedChange = { terms = it })
            Text(
                "I confirm these details are correct and agree to the OneFera seller terms, including selling only genuine products and shipping on time.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        err("acceptedTerms")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
        serverError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        GradientButton(
            text = if (submitting) "Submitting…" else "Submit registration",
            onClick = {
                showErrors = true
                if (errors.isEmpty()) onSubmit(draft)
            },
            loading = submitting,
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        )
        Text(
            "Your PAN and payout details are stored securely and never shown on your public profile.",
            style = MaterialTheme.typography.labelSmall,
            color = OneFeraTheme.extras.muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusView(
    profile: UserProfile,
    application: SellerApplication?,
    state: SellerRegistrationUiState,
    onEdit: () -> Unit,
    onMode: (AccountMode) -> Unit,
) {
    val actions = LocalAppActions.current
    val status = when {
        application != null -> application.status
        profile.accountMode == AccountMode.Seller -> SellerStatus.Approved
        else -> SellerStatus.None
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (status) {
            SellerStatus.Pending -> StatusCard(
                "⏳",
                "Registration under review",
                "We're checking your details" + (application?.submittedAt?.takeIf { it > 0 }?.let { " (submitted ${submittedDate(it)})" } ?: "") +
                    ". You'll be able to switch to Seller mode as soon as it's approved.",
                tag = "Pending",
            )
            SellerStatus.Rejected -> {
                StatusCard("✋", "Registration needs changes", application?.rejectionReason?.ifBlank { null } ?: "Some details couldn't be verified.", tag = "Action needed")
                GradientButton("Edit and resubmit", onClick = onEdit, modifier = Modifier.fillMaxWidth())
            }
            SellerStatus.Approved, SellerStatus.None -> {
                StatusCard(
                    "✅",
                    "You're a verified seller",
                    application?.storeName?.let { "Store: $it" } ?: "Your store is ready.",
                    tag = if (profile.accountMode == AccountMode.Seller) "Seller mode on" else "Approved",
                )
                if (profile.accountMode == AccountMode.Seller) {
                    GradientButton("Open Seller hub", onClick = actions.openSellerHub, modifier = Modifier.fillMaxWidth())
                    GlassButton("Switch to personal account", onClick = { onMode(AccountMode.Personal) }, enabled = !state.switching, modifier = Modifier.fillMaxWidth())
                } else {
                    GradientButton("Switch to Seller mode", onClick = { onMode(AccountMode.Seller) }, loading = state.switching, enabled = !state.switching, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        application?.let { a ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Your registration", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Detail("Store", a.storeName)
                Detail("Legal name", a.legalName)
                Detail("Category", a.category.label)
                Detail("PAN", a.pan.take(2) + "•••••" + a.pan.takeLast(2))
                if (a.gstin.isNotBlank()) Detail("GSTIN", a.gstin)
                Detail("Pickup", "${a.city}, ${a.state} ${a.pincode}")
                Detail("Payouts", if (a.payoutMethod == PayoutMethod.Upi) a.upiId else "${a.ifsc} ${a.accountNumber}")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun submittedDate(millis: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

@Composable
private fun StatusCard(emoji: String, title: String, body: String, tag: String? = null) {
    GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                tag?.let { Spacer(Modifier.height(4.dp)); GradientTag(it) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = OneFeraTheme.extras.muted)
    }
}

@Composable
private fun StatusBanner(title: String, body: String, color: androidx.compose.ui.graphics.Color) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = color)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
