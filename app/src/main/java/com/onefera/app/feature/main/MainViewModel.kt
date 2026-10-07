package com.onefera.app.feature.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.push.PushRegistrar
import com.onefera.app.data.settings.SettingsRepository
import com.onefera.app.data.social.NotificationRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.onefera.app.data.rewards.RewardsRepository
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
    private val pushRegistrar: PushRegistrar,
    chat: ChatRepository,
    private val settings: SettingsRepository,
    notifications: NotificationRepository,
    backendConfig: BackendConfig,
    private val rewards: RewardsRepository,
) : ViewModel() {

    /** Unread notifications, for the bell badge. */
    val unreadCount: StateFlow<Int> = notifications.unreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Conversations with unread messages, for the Chats tab badge. */
    val unreadChats: StateFlow<Int> = chat.totalUnread()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** True until we've asked once for the Android 13+ notification permission. */
    val shouldAskNotificationPermission: StateFlow<Boolean> = settings.settings.map { it.pushEnabled && !it.notificationPromptShown }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onNotificationPromptShown() {
        viewModelScope.launch { settings.setNotificationPromptShown() }
    }

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

    private var checkInJob: Job? = null

    init {
        // Daily check-in happens automatically the first time the app is opened each day.
        onAppVisible()
    }

    /**
     * Called when the app comes to the foreground. Checks in once per day: the profile already
     * says whether today is done, so this costs nothing on later resumes the same day, and a
     * phone that stays open past midnight still counts the new day.
     */
    fun onAppVisible() {
        if (checkInJob?.isActive == true) return
        checkInJob = viewModelScope.launch {
            val profile = state.first { it.profile != null }.profile ?: return@launch
            if (profile.checkedInToday()) return@launch
            rewards.checkIn()
                .onSuccess { r ->
                    if (!r.alreadyCheckedIn) {
                        delay(1_500) // let the screen settle so the snackbar is seen
                        _messages.emit("🔥 Day ${r.streak} streak · +${r.auraGained} Aura. Your Mystery Box is ready 🎁")
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Daily check-in failed", e)
                    _messages.emit("Couldn't check in today: ${e.message ?: "try again from Rewards"}")
                }
        }
    }

    fun toggleAccountMode() {
        val s = state.value
        val uid = s.uid ?: return
        val profile = s.profile ?: return
        val next = if (profile.accountMode == AccountMode.Seller) AccountMode.Personal else AccountMode.Seller
        viewModelScope.launch {
            users.setAccountMode(uid, next)
                .onSuccess {
                    _messages.emit(
                        if (next == AccountMode.Seller) "Switched to Seller account 🏪 Open the Seller hub from your profile."
                        else "Switched back to your personal account ✨",
                    )
                }
                .onFailure { _messages.emit(it.message ?: "Couldn't switch right now") }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            pushRegistrar.unregister()
            auth.signOut()
        }
    }

    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    private companion object {
        const val TAG = "OneFeraMain"
    }
}
