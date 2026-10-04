package com.onefera.app.feature.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.R
import com.onefera.app.core.common.PasswordStrength
import com.onefera.app.core.common.Validators
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.PasswordTextField
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.feature.auth.SignUpUiState.Field
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun SignUpScreen(
    isDemoMode: Boolean,
    onSignedUp: () -> Unit,
    onBack: () -> Unit,
    viewModel: SignUpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val extras = OneFeraTheme.extras
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    AuthBackground {
        AuthColumn {
            CircleIconButton(icon = R.drawable.ic_arrow_back, contentDescription = "Back", onClick = onBack)
            Spacer(Modifier.height(20.dp))
            Text("Join the", style = MaterialTheme.typography.displaySmall)
            Text(
                "future era ✦",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.gradientTint(extras.horizontalGradient()),
            )
            Spacer(Modifier.height(6.dp))
            Text("Takes 30 seconds. Your vibe, your shop, your circle.", style = MaterialTheme.typography.bodyLarge, color = extras.muted)
            Spacer(Modifier.height(24.dp))

            OneFeraTextField(
                value = state.displayName,
                onValueChange = viewModel::onNameChange,
                label = "Your name",
                leadingIcon = R.drawable.ic_person,
                error = state.errors[Field.Name],
                maxLength = Validators.NAME_MAX,
            )
            Spacer(Modifier.height(10.dp))
            OneFeraTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = "Username",
                leadingIcon = R.drawable.ic_at,
                error = state.errors[Field.Username],
                supportingText = when (state.usernameStatus) {
                    UsernameStatus.Available -> "@${state.username} is yours 🎉"
                    UsernameStatus.Taken -> "@${state.username} is taken"
                    UsernameStatus.Checking -> "Checking…"
                    else -> "Your handle, like @future.you"
                },
                keyboardType = KeyboardType.Ascii,
                trailing = { UsernameStatusIcon(state.usernameStatus) },
            )
            Spacer(Modifier.height(10.dp))
            OneFeraTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "Email",
                leadingIcon = R.drawable.ic_mail,
                error = state.errors[Field.Email],
                keyboardType = KeyboardType.Email,
            )
            Spacer(Modifier.height(10.dp))
            PasswordTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Password",
                error = state.errors[Field.Password],
                supportingText = "8+ characters with letters and numbers",
                imeAction = ImeAction.Done,
                onImeAction = { focus.clearFocus() },
            )
            PasswordStrengthBar(state.passwordStrength)
            Spacer(Modifier.height(10.dp))
            Box {
                OneFeraTextField(
                    value = state.birthDate?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "",
                    onValueChange = {},
                    label = "Birthday",
                    leadingIcon = R.drawable.ic_cake,
                    readOnly = true,
                    error = state.errors[Field.BirthDate],
                    supportingText = "Never shown on your profile",
                )
                // Transparent overlay so a tap anywhere opens the date picker.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            focus.clearFocus()
                            showDatePicker = true
                        },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.acceptedTerms,
                    onCheckedChange = viewModel::onTermsChange,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                )
                Text(
                    "I agree to the Terms and Community Guidelines and the Privacy Policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errors[Field.Terms] != null) MaterialTheme.colorScheme.error else extras.muted,
                )
            }
            FormError(state.formError)
            Spacer(Modifier.height(12.dp))
            GradientButton(
                text = "Create account",
                loading = state.loading,
                onClick = {
                    focus.clearFocus()
                    viewModel.submit(onSignedUp)
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Already vibing here?", color = extras.muted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onBack) { Text("Log in") }
            }
            if (isDemoMode) {
                DemoModeNotice(lines = listOf("Firebase isn't connected yet. Your account is saved on this phone only."))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        BirthdayPickerDialog(
            initial = state.birthDate,
            onDismiss = { showDatePicker = false },
            onPicked = {
                viewModel.onBirthDateChange(it)
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun UsernameStatusIcon(status: UsernameStatus) {
    when (status) {
        UsernameStatus.Checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        UsernameStatus.Available -> Icon(painterResource(R.drawable.ic_check), contentDescription = "Available", tint = OneFeraTheme.extras.success, modifier = Modifier.size(20.dp))
        UsernameStatus.Taken -> Icon(painterResource(R.drawable.ic_close), contentDescription = "Taken", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        else -> Unit
    }
}

@Composable
private fun PasswordStrengthBar(strength: PasswordStrength) {
    if (strength == PasswordStrength.None) return
    val extras = OneFeraTheme.extras
    val fraction by animateFloatAsState(strength.fraction, label = "strength")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(extras.glassBorder),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(extras.horizontalGradient()),
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(strength.label, style = MaterialTheme.typography.labelMedium, color = extras.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPickerDialog(initial: LocalDate?, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val todayMillis = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: today.minusYears(18)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        yearRange = (today.year - 100)..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = {
                    pickerState.selectedDateMillis?.let {
                        onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}
