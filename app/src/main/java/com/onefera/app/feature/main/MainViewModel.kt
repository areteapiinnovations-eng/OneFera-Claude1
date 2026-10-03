package com.onefera.app.feature.main

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
import com.onefera.app.data.social.StoryRepository
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val stories: StoryRepository,
    private val pushRegistrar: PushRegistrar,
    chat: ChatRepository,
    private val settings: SettingsRepository,
    notifications: NotificationRepository,
    backendConfig: BackendConfig,
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

    private val _storyUpload = MutableStateFlow<Float?>(null)
    /** Upload progress (0..1) while a story is being posted, otherwise null. */
    val storyUpload: StateFlow<Float?> = _storyUpload.asStateFlow()

    fun addStory(image: android.net.Uri) {
        if (_storyUpload.value != null) return
        _storyUpload.value = 0f
        viewModelScope.launch {
            stories.addStory(image) { _storyUpload.value = it }
                .onSuccess { _messages.emit("Story posted ✨ it disappears in 24 h") }
                .onFailure { _messages.emit(it.message ?: "Couldn't post your story") }
            _storyUpload.value = null
        }
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
}
