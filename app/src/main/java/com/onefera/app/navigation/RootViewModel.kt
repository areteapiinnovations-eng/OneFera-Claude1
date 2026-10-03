package com.onefera.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.settings.AppSettings
import com.onefera.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RootUiState {
    data object Loading : RootUiState
    data class Ready(
        val settings: AppSettings,
        val startDestination: Any,
        val isSignedIn: Boolean,
        val isDemoMode: Boolean,
    ) : RootUiState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    authRepository: AuthRepository,
    backendConfig: BackendConfig,
) : ViewModel() {

    // The start destination is decided once, from the first restored state.
    private var startDestination: Any? = null

    val uiState: StateFlow<RootUiState> = combine(settingsRepository.settings, authRepository.session) { settings, session ->
        if (session is SessionState.Unknown) return@combine RootUiState.Loading
        val signedIn = session is SessionState.SignedIn
        val start = startDestination ?: when {
            !settings.onboardingSeen -> OnboardingRoute
            signedIn -> MainRoute
            else -> SignInRoute
        }.also { startDestination = it }
        RootUiState.Ready(settings, start, signedIn, backendConfig.isDemoMode)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState.Loading)
}
