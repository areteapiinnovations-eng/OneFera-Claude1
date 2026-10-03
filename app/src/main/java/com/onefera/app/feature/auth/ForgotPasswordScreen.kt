package com.onefera.app.feature.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.common.Validators
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ForgotPasswordUiState(
    val email: String = "",
    val error: String? = null,
    val loading: Boolean = false,
    val sent: Boolean = false,
)

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun send() {
        val s = _state.value
        if (s.loading) return
        Validators.email(s.email)?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            auth.sendPasswordReset(s.email)
                .onSuccess { _state.update { it.copy(loading = false, sent = true) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, viewModel: ForgotPasswordViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthBackground {
        AuthColumn {
            CircleIconButton(icon = R.drawable.ic_arrow_back, contentDescription = "Back", onClick = onBack)
            Spacer(Modifier.height(32.dp))
            if (state.sent) {
                EmptyState(
                    icon = R.drawable.ic_mail,
                    title = "Check your inbox 📬",
                    message = "We sent a reset link to ${state.email.trim()}. It can take a minute, and peek in spam just in case.",
                    action = { GlassButton(text = "Back to log in", onClick = onBack) },
                )
            } else {
                Text("Forgot your password?", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "No stress. Drop your email and we'll send you a reset link.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OneFeraTheme.extras.muted,
                )
                Spacer(Modifier.height(28.dp))
                OneFeraTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = "Email",
                    leadingIcon = R.drawable.ic_mail,
                    error = state.error,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Send,
                    onImeAction = viewModel::send,
                )
                Spacer(Modifier.height(20.dp))
                GradientButton(text = "Send reset link", loading = state.loading, onClick = viewModel::send)
            }
        }
    }
}
