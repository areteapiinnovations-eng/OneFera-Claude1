package com.onefera.app.feature.near

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.LatLng
import com.onefera.app.data.model.NearRadius
import com.onefera.app.data.model.NearSettings
import com.onefera.app.data.model.NearbyPerson
import com.onefera.app.data.model.NearbyStore
import com.onefera.app.data.near.LocationProvider
import com.onefera.app.data.near.NearRepository
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.onefera.app.data.moderation.ModerationRepository
import javax.inject.Inject

enum class NearMode(val label: String) { People("👋 People"), Stores("🏪 Stores") }

data class NearUiState(
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val location: LatLng? = null,
    val usingDemoLocation: Boolean = false,
    val mode: NearMode = NearMode.People,
    val radius: NearRadius = NearRadius.Around,
    val people: List<NearbyPerson> = emptyList(),
    val stores: List<NearbyStore> = emptyList(),
    val settings: NearSettings = NearSettings(),
    val isMinor: Boolean = false,
    val isSeller: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NearViewModel @Inject constructor(
    auth: AuthRepository,
    users: UserRepository,
    private val near: NearRepository,
    private val locations: LocationProvider,
    private val config: BackendConfig,
    moderation: ModerationRepository,
) : ViewModel() {
    private val local = MutableStateFlow(NearUiState(hasPermission = locations.hasPermission()))
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    private var loadJob: Job? = null

    val state: StateFlow<NearUiState> = combine(
        local,
        near.settings(),
        auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(null) else users.observeProfile(uid) },
    ) { s, settings, profile -> Triple(s, settings, profile) }
        .let { base ->
            combine(base, moderation.blockedIds()) { (s, settings, profile), blocked ->
                s.copy(
                    settings = settings,
                    isMinor = profile?.isMinor == true,
                    isSeller = profile?.accountMode == AccountMode.Seller,
                    people = s.people.filter { it.user.uid !in blocked },
                    stores = s.stores.filter { it.seller.uid !in blocked },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun start() {
        val granted = locations.hasPermission()
        local.update { it.copy(hasPermission = granted) }
        if (granted && local.value.location == null) locate()
    }

    fun onPermissionResult(granted: Boolean) {
        local.update { it.copy(hasPermission = granted) }
        if (granted) locate() else viewModelScope.launch { _messages.send("No worries. Near stays off until you allow location.") }
    }

    private fun locate() {
        local.update { it.copy(loading = true) }
        viewModelScope.launch {
            val found = locations.current()
            // Emulators and some indoor devices have no fix; the demo backend falls back to a demo spot.
            val location = found ?: if (config.isDemoMode) DEMO_SPOT else null
            if (location == null) {
                local.update { it.copy(loading = false) }
                _messages.send("Couldn't get your location. Check that location is on and try again.")
                return@launch
            }
            local.update { it.copy(location = location, usingDemoLocation = found == null) }
            near.refresh(location)
            load()
        }
    }

    fun onMode(mode: NearMode) {
        local.update { it.copy(mode = mode) }
        load()
    }

    fun onRadius(radius: NearRadius) {
        local.update { it.copy(radius = radius) }
        load()
    }

    private fun load() {
        val s = local.value
        val location = s.location ?: return
        loadJob?.cancel()
        local.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            if (s.mode == NearMode.People) {
                near.people(location, s.radius)
                    .onSuccess { list -> local.update { it.copy(people = list) } }
                    .onFailure { _messages.send(it.message ?: "Couldn't load people nearby.") }
            } else {
                near.stores(location, s.radius)
                    .onSuccess { list -> local.update { it.copy(stores = list) } }
                    .onFailure { _messages.send(it.message ?: "Couldn't load stores nearby.") }
            }
            local.update { it.copy(loading = false) }
        }
    }

    fun setVisible(visible: Boolean) = viewModelScope.launch {
        near.setVisible(visible, local.value.location)
            .onSuccess { _messages.send(if (visible) "You're on Near 📍 (approximate area only)" else "You're hidden from Near") }
            .onFailure { _messages.send(it.message ?: "Couldn't update Near.") }
    }

    fun setStoreShared(shared: Boolean) = viewModelScope.launch {
        near.setStoreShared(shared, local.value.location)
            .onSuccess {
                _messages.send(if (shared) "Your store is on Near 🏪" else "Your store is hidden from Near")
                if (local.value.mode == NearMode.Stores) load()
            }
            .onFailure { _messages.send(it.message ?: "Couldn't update your store.") }
    }

    private companion object {
        val DEMO_SPOT = LatLng(19.08, 72.88)
    }
}
