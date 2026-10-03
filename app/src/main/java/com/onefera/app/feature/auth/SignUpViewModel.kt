package com.onefera.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.core.common.PasswordStrength
import com.onefera.app.core.common.Validators
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class UsernameStatus { Idle, Checking, Available, Taken, Unknown }

data class SignUpUiState(
    val displayName: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val birthDate: LocalDate? = null,
    val acceptedTerms: Boolean = false,
    val usernameStatus: UsernameStatus = UsernameStatus.Idle,
    /** True once the user typed their own handle, so we stop suggesting one from their name. */
    val usernameTouched: Boolean = false,
    val errors: Map<Field, String> = emptyMap(),
    val formError: String? = null,
    val loading: Boolean = false,
) {
    enum class Field { Name, Username, Email, Password, BirthDate, Terms }

    val passwordStrength: PasswordStrength get() = Validators.passwordStrength(password)
}

@HiltViewModel
class SignUpViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()
    private var usernameJob: Job? = null

    private fun edit(field: SignUpUiState.Field, transform: (SignUpUiState) -> SignUpUiState) =
        _state.update { transform(it).copy(errors = it.errors - field, formError = null) }

    fun onNameChange(v: String) {
        edit(SignUpUiState.Field.Name) { it.copy(displayName = v) }
        // Suggest a handle from the name until the user types their own.
        if (!_state.value.usernameTouched) {
            val suggestion = Validators.normalizeUsername(v.replace(' ', '.')).trim('.').take(Validators.USERNAME_MAX)
            setUsername(suggestion, touched = false)
        }
    }

    fun onUsernameChange(v: String) = setUsername(v, touched = true)

    private fun setUsername(v: String, touched: Boolean) {
        val normalized = Validators.normalizeUsername(v).take(Validators.USERNAME_MAX)
        edit(SignUpUiState.Field.Username) {
            it.copy(username = normalized, usernameStatus = UsernameStatus.Idle, usernameTouched = it.usernameTouched || touched)
        }
        usernameJob?.cancel()
        if (Validators.username(normalized) != null) return
        usernameJob = viewModelScope.launch {
            delay(450)
            _state.update { it.copy(usernameStatus = UsernameStatus.Checking) }
            val status = runCatching { users.isUsernameAvailable(normalized) }
                .map { if (it) UsernameStatus.Available else UsernameStatus.Taken }
                .getOrDefault(UsernameStatus.Unknown)
            _state.update { if (it.username == normalized) it.copy(usernameStatus = status) else it }
        }
    }

    fun onEmailChange(v: String) = edit(SignUpUiState.Field.Email) { it.copy(email = v) }
    fun onPasswordChange(v: String) = edit(SignUpUiState.Field.Password) { it.copy(password = v) }
    fun onBirthDateChange(v: LocalDate) = edit(SignUpUiState.Field.BirthDate) { it.copy(birthDate = v) }
    fun onTermsChange(v: Boolean) = edit(SignUpUiState.Field.Terms) { it.copy(acceptedTerms = v) }

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.loading) return
        val errors = buildMap {
            Validators.displayName(s.displayName)?.let { put(SignUpUiState.Field.Name, it) }
            (Validators.username(s.username) ?: if (s.usernameStatus == UsernameStatus.Taken) "That handle is taken" else null)
                ?.let { put(SignUpUiState.Field.Username, it) }
            Validators.email(s.email)?.let { put(SignUpUiState.Field.Email, it) }
            Validators.password(s.password)?.let { put(SignUpUiState.Field.Password, it) }
            Validators.birthDate(s.birthDate)?.let { put(SignUpUiState.Field.BirthDate, it) }
            if (!s.acceptedTerms) put(SignUpUiState.Field.Terms, "Please accept to continue")
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(loading = true, formError = null) }
        viewModelScope.launch {
            val failure = createAccount(s)
            if (failure == null) {
                _state.update { it.copy(loading = false) }
                onSuccess()
            } else {
                _state.update { it.copy(loading = false, formError = failure) }
            }
        }
    }

    /** Returns null on success, otherwise a message to show. */
    private suspend fun createAccount(s: SignUpUiState): String? {
        val available = runCatching { users.isUsernameAvailable(s.username) }.getOrDefault(true)
        if (!available) {
            _state.update { it.copy(usernameStatus = UsernameStatus.Taken, errors = it.errors + (SignUpUiState.Field.Username to "That handle is taken")) }
            return "Pick another handle and try again."
        }
        val uid = auth.signUp(s.email, s.password).getOrElse { return it.message }
        val birthDate = requireNotNull(s.birthDate)
        val isMinor = Validators.ageOn(birthDate) < Validators.ADULT_AGE
        val profile = UserProfile(
            uid = uid,
            displayName = s.displayName.trim(),
            username = s.username,
            email = s.email.trim(),
            birthDate = birthDate.toString(),
            isMinor = isMinor,
            // Teen accounts start private for safety; they can change it later in Settings.
            isPrivate = isMinor,
        )
        users.createProfile(profile).onFailure { error ->
            auth.deleteCurrentAccount()
            return error.message
        }
        return null
    }
}
