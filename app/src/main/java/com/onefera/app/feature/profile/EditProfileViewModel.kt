package com.onefera.app.feature.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.core.common.Validators
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.ProfileUpdate
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class EditProfileUiState(
    val loading: Boolean = true,
    val uid: String = "",
    val email: String = "",
    val existing: UserProfile? = null,
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    val vibe: String = "",
    val city: String = "",
    val avatarUrl: String? = null,
    val uploadingPhoto: Boolean = false,
    val usernameError: String? = null,
    val nameError: String? = null,
    val formError: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()
    private var usernameJob: Job? = null

    init {
        viewModelScope.launch {
            val session = auth.session.first { it !is SessionState.Unknown } as? SessionState.SignedIn ?: return@launch
            val profile = withTimeoutOrNull(5_000) { users.observeProfile(session.uid).first() }
            _state.update {
                it.copy(
                    loading = false,
                    uid = session.uid,
                    email = session.email,
                    existing = profile,
                    displayName = profile?.displayName.orEmpty(),
                    username = profile?.username.orEmpty(),
                    bio = profile?.bio.orEmpty(),
                    vibe = profile?.vibe.orEmpty(),
                    city = profile?.city.orEmpty(),
                    avatarUrl = profile?.avatarUrl,
                )
            }
        }
    }

    fun onNameChange(v: String) = _state.update { it.copy(displayName = v, nameError = null, formError = null) }
    fun onBioChange(v: String) = _state.update { it.copy(bio = v.take(Validators.BIO_MAX), formError = null) }
    fun onCityChange(v: String) = _state.update { it.copy(city = v.take(40), formError = null) }
    fun onVibeChange(v: String) = _state.update { it.copy(vibe = if (it.vibe == v) "" else v) }

    fun onUsernameChange(v: String) {
        val normalized = Validators.normalizeUsername(v).take(Validators.USERNAME_MAX)
        _state.update { it.copy(username = normalized, usernameError = null, formError = null) }
        usernameJob?.cancel()
        if (Validators.username(normalized) != null || normalized == _state.value.existing?.username) return
        usernameJob = viewModelScope.launch {
            delay(450)
            val available = runCatching { users.isUsernameAvailable(normalized, _state.value.uid) }.getOrDefault(true)
            if (!available) _state.update { if (it.username == normalized) it.copy(usernameError = "@$normalized is taken") else it }
        }
    }

    fun onPhotoPicked(uri: Uri) {
        val uid = _state.value.uid.ifEmpty { return }
        _state.update { it.copy(uploadingPhoto = true, formError = null) }
        viewModelScope.launch {
            users.uploadAvatar(uid, uri)
                .onSuccess { url -> _state.update { it.copy(uploadingPhoto = false, avatarUrl = url) } }
                .onFailure { e -> _state.update { it.copy(uploadingPhoto = false, formError = e.message) } }
        }
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val nameError = Validators.displayName(s.displayName)
        val usernameError = Validators.username(s.username) ?: s.usernameError
        if (nameError != null || usernameError != null) {
            _state.update { it.copy(nameError = nameError, usernameError = usernameError) }
            return
        }
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            val result = if (s.existing != null) {
                users.updateProfile(s.uid, ProfileUpdate(s.displayName.trim(), s.username, s.bio.trim(), s.vibe, s.city.trim()))
            } else {
                users.createProfile(
                    UserProfile(
                        uid = s.uid,
                        email = s.email,
                        displayName = s.displayName.trim(),
                        username = s.username,
                        bio = s.bio.trim(),
                        vibe = s.vibe,
                        city = s.city.trim(),
                        avatarUrl = s.avatarUrl,
                    ),
                )
            }
            result
                .onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, formError = e.message) } }
        }
    }
}
