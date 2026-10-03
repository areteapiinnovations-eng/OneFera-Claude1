package com.onefera.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val loading: Boolean = true,
    val uid: String? = null,
    val email: String = "",
    val profile: UserProfile? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
    backendConfig: BackendConfig,
) : ViewModel() {

    val isDemoMode = backendConfig.isDemoMode

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for the snackbar. */
    val messages: SharedFlow<String> = _messages

    val state: StateFlow<MainUiState> = auth.session
        .flatMapLatest { session ->
            if (session is SessionState.SignedIn) {
                users.observeProfile(session.uid).map { MainUiState(loading = false, uid = session.uid, email = session.email, profile = it) }
            } else {
                flowOf(MainUiState(loading = session is SessionState.Unknown))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun toggleAccountMode() {
        val s = state.value
        val uid = s.uid ?: return
        val profile = s.profile ?: return
        val next = if (profile.accountMode == AccountMode.Seller) AccountMode.Personal else AccountMode.Seller
        viewModelScope.launch {
            users.setAccountMode(uid, next)
                .onSuccess {
                    _messages.emit(
                        if (next == AccountMode.Seller) "Switched to Seller account 🏪 Seller tools are coming in the next build."
                        else "Switched back to your personal account ✨",
                    )
                }
                .onFailure { _messages.emit(it.message ?: "Couldn't switch right now") }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }
}
