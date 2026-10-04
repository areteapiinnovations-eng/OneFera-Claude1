package com.onefera.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.core.designsystem.theme.ThemeMode
import com.onefera.app.core.designsystem.theme.ThemeSkin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Device-level preferences (theme, onboarding, notifications). */
data class AppSettings(
    val skin: ThemeSkin = ThemeSkin.Default,
    val themeMode: ThemeMode = ThemeMode.Default,
    val onboardingSeen: Boolean = false,
    val pushEnabled: Boolean = true,
    val recentSearches: List<String> = emptyList(),
    val notificationPromptShown: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data
        .map { prefs ->
            AppSettings(
                skin = ThemeSkin.fromName(prefs[Keys.SKIN]),
                themeMode = ThemeMode.fromName(prefs[Keys.THEME_MODE]),
                onboardingSeen = prefs[Keys.ONBOARDING_SEEN] ?: false,
                pushEnabled = prefs[Keys.PUSH_ENABLED] ?: true,
                notificationPromptShown = prefs[Keys.NOTIFICATION_PROMPT] ?: false,
                recentSearches = prefs[Keys.RECENT_SEARCHES]?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty(),
            )
        }
        .distinctUntilChanged()

    suspend fun setSkin(skin: ThemeSkin) = context.settingsStore.edit { it[Keys.SKIN] = skin.name }

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsStore.edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setOnboardingSeen() = context.settingsStore.edit { it[Keys.ONBOARDING_SEEN] = true }

    suspend fun setPushEnabled(enabled: Boolean) = context.settingsStore.edit { it[Keys.PUSH_ENABLED] = enabled }

    /** Remembers a search term (most recent first, max 12). */
    suspend fun addRecentSearch(term: String) = context.settingsStore.edit { prefs ->
        val clean = term.trim().replace(SEPARATOR, "")
        if (clean.isEmpty()) return@edit
        val current = prefs[Keys.RECENT_SEARCHES]?.split(SEPARATOR).orEmpty().filter { it.isNotBlank() }
        prefs[Keys.RECENT_SEARCHES] = (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(12).joinToString(SEPARATOR)
    }

    suspend fun removeRecentSearch(term: String) = context.settingsStore.edit { prefs ->
        val current = prefs[Keys.RECENT_SEARCHES]?.split(SEPARATOR).orEmpty()
        prefs[Keys.RECENT_SEARCHES] = current.filterNot { it == term }.joinToString(SEPARATOR)
    }

    suspend fun setNotificationPromptShown() = context.settingsStore.edit { it[Keys.NOTIFICATION_PROMPT] = true }

    suspend fun clearRecentSearches() = context.settingsStore.edit { it.remove(Keys.RECENT_SEARCHES) }

    private companion object {
        const val SEPARATOR = "\u001F"
    }

    private object Keys {
        val SKIN = stringPreferencesKey("skin")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val NOTIFICATION_PROMPT = booleanPreferencesKey("notification_prompt_shown")
    }
}
