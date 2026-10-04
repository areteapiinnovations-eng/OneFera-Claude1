package com.onefera.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraMark
import com.onefera.app.core.designsystem.component.OneFeraTagline
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.PasswordTextField
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.demo.DemoBackend

@Composable
fun SignInScreen(
    isDemoMode: Boolean,
    onSignedIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val extras = OneFeraTheme.extras

    AuthBackground {
        AuthColumn {
            Spacer(Modifier.height(32.dp))
            OneFeraMark(Modifier.width(84.dp))
            Spacer(Modifier.height(28.dp))
            Text("Welcome back", style = MaterialTheme.typography.displaySmall)
            Text(
                "to your future era ✦",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.gradientTint(extras.horizontalGradient()),
            )
            Spacer(Modifier.height(8.dp))
            OneFeraTagline()
            Spacer(Modifier.height(32.dp))

            OneFeraTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "Email",
                leadingIcon = R.drawable.ic_mail,
                error = state.emailError,
                keyboardType = KeyboardType.Email,
            )
            Spacer(Modifier.height(12.dp))
            PasswordTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Password",
                error = state.passwordError,
                imeAction = ImeAction.Done,
                onImeAction = {
                    focus.clearFocus()
                    viewModel.signIn(onSignedIn)
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onForgotPassword) { Text("Forgot password?") }
            }
            FormError(state.formError)
            Spacer(Modifier.height(8.dp))
            GradientButton(
                text = "Log in",
                loading = state.loading,
                onClick = {
                    focus.clearFocus()
                    viewModel.signIn(onSignedIn)
                },
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("New to OneFera?", color = extras.muted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCreateAccount) { Text("Create account") }
            }

            if (isDemoMode) {
                Spacer(Modifier.height(16.dp))
                DemoModeNotice(
                    lines = listOf(
                        "Firebase isn't connected yet, so everything stays on this phone.",
                        "Try ${DemoBackend.DEMO_EMAIL} / ${DemoBackend.DEMO_PASSWORD} or create a new account.",
                    ),
                )
                TextButton(onClick = { viewModel.fill(DemoBackend.DEMO_EMAIL, DemoBackend.DEMO_PASSWORD) }) {
                    Text("Fill demo login")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
