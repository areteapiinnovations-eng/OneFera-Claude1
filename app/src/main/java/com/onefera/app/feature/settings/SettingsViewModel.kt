package com.onefera.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.core.designsystem.theme.ThemeMode
import com.onefera.app.core.designsystem.theme.ThemeSkin
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.settings.AppSettings
import com.onefera.app.data.settings.SettingsRepository
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isPrivate: Boolean = false,
    val canEditPrivacy: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val auth: AuthRepository,
    private val users: UserRepository,
) : ViewModel() {

    private val profile = auth.session.flatMapLatest { session ->
        if (session is SessionState.SignedIn) users.observeProfile(session.uid) else flowOf(null)
    }

    val state: StateFlow<SettingsUiState> = combine(settingsRepository.settings, profile) { settings, profile ->
        SettingsUiState(settings = settings, isPrivate = profile?.isPrivate ?: false, canEditPrivacy = profile != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setSkin(skin: ThemeSkin) = viewModelScope.launch { settingsRepository.setSkin(skin) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setPush(enabled: Boolean) = viewModelScope.launch { settingsRepository.setPushEnabled(enabled) }

    fun setPrivate(isPrivate: Boolean) {
        val session = auth.session.value as? SessionState.SignedIn ?: return
        viewModelScope.launch { users.setPrivate(session.uid, isPrivate) }
    }
}
