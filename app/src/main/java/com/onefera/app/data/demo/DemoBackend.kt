package com.onefera.app.data.demo

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.ProfileUpdate
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoStore: DataStore<Preferences> by preferencesDataStore(name = "demo_backend")

/**
 * Offline stand-in for Firebase, used when the app is built without google-services.json.
 * Accounts and profiles are kept on this device only, so testers can try every flow
 * before the real backend is connected.
 */
@Singleton
class DemoBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Serializable
    private data class Account(val email: String, val password: String, val profile: UserProfile)

    @Serializable
    private data class State(val accounts: List<Account> = emptyList(), val sessionUid: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<State?>(null)

    init {
        scope.launch {
            val saved = context.demoStore.data.first()[KEY]?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() }
            val base = saved ?: State()
            // Add any seed accounts (the demo login and the demo creators) that aren't there yet.
            val seeds = listOf(seedAccount()) + DemoSeed.creators.map { Account(it.email, DemoSeed.CREATOR_PASSWORD, it) }
            val missing = seeds.filter { seed -> base.accounts.none { it.profile.uid == seed.profile.uid } }
            state.value = base.copy(accounts = base.accounts + missing)
        }
    }

    val session: Flow<SessionState> = state.map { s ->
        when {
            s == null -> SessionState.Unknown
            s.sessionUid == null -> SessionState.SignedOut
            else -> s.accounts.firstOrNull { it.profile.uid == s.sessionUid }
                ?.let { SessionState.SignedIn(it.profile.uid, it.email) } ?: SessionState.SignedOut
        }
    }.distinctUntilChanged()

    /** Every profile in the demo world (used for search, suggestions and follow lists). */
    val profiles: Flow<List<UserProfile>> = state.map { s -> s?.accounts?.map { it.profile }.orEmpty() }.distinctUntilChanged()

    suspend fun profileNow(uid: String): UserProfile? = current().accounts.firstOrNull { it.profile.uid == uid }?.profile

    suspend fun currentUid(): String? = current().sessionUid

    /** Updates profile fields such as counters immediately (no simulated latency). */
    suspend fun adjustProfile(uid: String, transform: (UserProfile) -> UserProfile) {
        update(latencyMs = 0) { s ->
            s.copy(accounts = s.accounts.map { if (it.profile.uid == uid) it.copy(profile = transform(it.profile)) else it }) to Unit
        }
    }

    fun profile(uid: String): Flow<UserProfile?> =
        state.map { s -> s?.accounts?.firstOrNull { it.profile.uid == uid }?.profile }.distinctUntilChanged()

    private suspend fun current(): State = state.first { it != null }!!

    private suspend fun <T> update(latencyMs: Long = 350, block: (State) -> Pair<State, T>): T = mutex.withLock {
        if (latencyMs > 0) delay(latencyMs) // feel like a network call
        val (next, result) = block(current())
        state.value = next
        context.demoStore.edit { it[KEY] = json.encodeToString(State.serializer(), next) }
        result
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        update { s ->
            val account = s.accounts.firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }
                ?: throw UserFacingException("We couldn't find an account with that email.")
            if (account.password != password) throw UserFacingException("Email or password is incorrect.")
            s.copy(sessionUid = account.profile.uid) to Unit
        }
    }

    suspend fun signUp(email: String, password: String): Result<String> = runCatching {
        update { s ->
            if (s.accounts.any { it.email.equals(email.trim(), ignoreCase = true) }) {
                throw UserFacingException("An account with this email already exists. Try signing in.")
            }
            val uid = "demo-" + UUID.randomUUID().toString().take(12)
            val account = Account(email.trim(), password, UserProfile(uid = uid, email = email.trim()))
            s.copy(accounts = s.accounts + account, sessionUid = uid) to uid
        }
    }

    suspend fun signOut() {
        update { s -> s.copy(sessionUid = null) to Unit }
    }

    suspend fun deleteSessionAccount() {
        update { s -> s.copy(accounts = s.accounts.filterNot { it.profile.uid == s.sessionUid }, sessionUid = null) to Unit }
    }

    suspend fun accountExists(email: String): Boolean = current().accounts.any { it.email.equals(email.trim(), ignoreCase = true) }

    suspend fun usernameOwner(username: String): String? = current().accounts.firstOrNull { it.profile.username == username }?.profile?.uid

    suspend fun saveProfile(uid: String, transform: (UserProfile) -> UserProfile): Result<Unit> = runCatching {
        update { s ->
            val index = s.accounts.indexOfFirst { it.profile.uid == uid }
            if (index < 0) throw UserFacingException("Account not found.")
            val updated = s.accounts[index].let { it.copy(profile = transform(it.profile)) }
            val clash = s.accounts.any { it.profile.uid != uid && it.profile.username == updated.profile.username && updated.profile.username.isNotEmpty() }
            if (clash) throw UserFacingException("@${updated.profile.username} is taken. Try another one.")
            s.copy(accounts = s.accounts.toMutableList().also { it[index] = updated }) to Unit
        }
    }

    /** Copies the picked image into app storage so it survives restarts, returning a file URI. */
    suspend fun storeAvatar(uid: String, image: Uri): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "demo_avatars").apply { mkdirs() }
            val file = File(dir, "$uid-${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(image)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                ?: throw UserFacingException("Couldn't read that photo.")
            dir.listFiles()?.filter { it.name.startsWith("$uid-") && it != file }?.forEach { it.delete() }
            Uri.fromFile(file).toString()
        }
    }

    private fun seedAccount() = Account(
        email = DEMO_EMAIL,
        password = DEMO_PASSWORD,
        profile = UserProfile(
            uid = "demo-founder",
            displayName = "Future Explorer",
            username = "onefera.demo",
            email = DEMO_EMAIL,
            bio = "Living in the future era ✦ shopping drops, sharing vibes.",
            vibe = "Main Character",
            city = "Hyderabad",
            birthDate = "2004-06-15",
            verified = true,
            auraPoints = 525,
            streakDays = 3,
            postsCount = 6,
            followersCount = 11,
            followingCount = 14,
            profileViews = 11,
            createdAt = System.currentTimeMillis(),
        ),
    )

    companion object {
        const val DEMO_EMAIL = "demo@onefera.app"
        const val DEMO_PASSWORD = "onefera123"
        private val KEY = stringPreferencesKey("state")
    }
}

@Singleton
class DemoAuthRepository @Inject constructor(
    private val backend: DemoBackend,
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {
    private val _session = MutableStateFlow<SessionState>(SessionState.Unknown)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
        scope.launch { backend.session.collect { _session.value = it } }
    }

    override suspend fun signIn(email: String, password: String) = backend.signIn(email, password)
    override suspend fun signUp(email: String, password: String) = backend.signUp(email, password)

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        delay(400)
        return if (backend.accountExists(email)) {
            Result.success(Unit)
        } else {
            Result.failure(UserFacingException("We couldn't find an account with that email."))
        }
    }

    override suspend fun signOut() = backend.signOut()
    override suspend fun deleteCurrentAccount() = backend.deleteSessionAccount()
}

@Singleton
class DemoUserRepository @Inject constructor(private val backend: DemoBackend) : UserRepository {
    override fun observeProfile(uid: String): Flow<UserProfile?> =
        backend.profile(uid).map { profile -> profile?.takeIf { it.username.isNotEmpty() } }

    override suspend fun isUsernameAvailable(username: String, forUid: String?): Boolean {
        val owner = backend.usernameOwner(username)
        return owner == null || owner == forUid
    }

    override suspend fun createProfile(profile: UserProfile): Result<Unit> =
        backend.saveProfile(profile.uid) { profile.copy(createdAt = System.currentTimeMillis()) }

    override suspend fun updateProfile(uid: String, update: ProfileUpdate): Result<Unit> =
        backend.saveProfile(uid) {
            it.copy(displayName = update.displayName, username = update.username, bio = update.bio, vibe = update.vibe, city = update.city)
        }

    override suspend fun uploadAvatar(uid: String, image: Uri): Result<String> =
        backend.storeAvatar(uid, image).mapCatching { url ->
            backend.saveProfile(uid) { it.copy(avatarUrl = url) }.getOrThrow()
            url
        }

    override suspend fun setAccountMode(uid: String, mode: AccountMode): Result<Unit> =
        backend.saveProfile(uid) { it.copy(accountMode = mode) }

    override suspend fun setPrivate(uid: String, isPrivate: Boolean): Result<Unit> =
        backend.saveProfile(uid) { it.copy(isPrivate = isPrivate) }

    override suspend fun touchLastActive(uid: String) =
        backend.adjustProfile(uid) { it.copy(lastActiveAt = System.currentTimeMillis()) }
}
