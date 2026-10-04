package com.onefera.app.data.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.moderation.ModerationRepository
import com.onefera.app.data.moderation.ReportReason
import com.onefera.app.data.moderation.ReportTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoModerationStore: DataStore<Preferences> by preferencesDataStore(name = "demo_moderation")

/** Demo blocking (stored on the device) and reports (accepted, then discarded). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoModerationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val social: DemoSocialBackend,
) : ModerationRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ListSerializer(UserSummary.serializer()))

    private val all: Flow<Map<String, List<UserSummary>>> = context.demoModerationStore.data.map { p ->
        p[KEY]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    private fun uid(): String = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")

    override fun blockedUsers(): Flow<List<UserSummary>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList()) else all.map { it[uid].orEmpty() }.distinctUntilChanged()
    }

    override fun blockedIds(): Flow<Set<String>> = blockedUsers().map { list -> list.map { it.uid }.toSet() }

    private suspend fun edit(uid: String, transform: (List<UserSummary>) -> List<UserSummary>) {
        context.demoModerationStore.edit { p ->
            val current = p[KEY]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
            p[KEY] = json.encodeToString(serializer, current + (uid to transform(current[uid].orEmpty())))
        }
    }

    override suspend fun block(user: UserSummary): Result<Unit> = runCatching {
        val me = uid()
        if (user.uid == me) throw UserFacingException("You can't block yourself.")
        edit(me) { list -> list.filterNot { it.uid == user.uid } + user }
        runCatching { social.unfollow(me, user.uid) }
        runCatching { social.unfollow(user.uid, me) }
        Unit
    }

    override suspend fun unblock(uid: String): Result<Unit> = runCatching {
        edit(uid()) { list -> list.filterNot { it.uid == uid } }
    }

    override suspend fun report(target: ReportTarget, targetId: String, ownerId: String, reason: ReportReason, details: String): Result<Unit> = runCatching {
        uid()
        delay(300)
    }

    private companion object {
        val KEY = stringPreferencesKey("blocked")
    }
}
