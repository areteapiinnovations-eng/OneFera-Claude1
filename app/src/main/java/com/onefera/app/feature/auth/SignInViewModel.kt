package com.onefera.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.core.common.Validators
import com.onefera.app.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class SignInViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, formError = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun fill(email: String, password: String) = _state.update { it.copy(email = email, password = password) }

    fun signIn(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.loading) return
        val emailError = Validators.email(s.email)
        val passwordError = if (s.password.isEmpty()) "Enter your password" else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(loading = true, formError = null) }
        viewModelScope.launch {
            auth.signIn(s.email, s.password)
                .onSuccess {
                    _state.update { it.copy(loading = false) }
                    onSuccess()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, formError = e.message) } }
        }
    }
}
