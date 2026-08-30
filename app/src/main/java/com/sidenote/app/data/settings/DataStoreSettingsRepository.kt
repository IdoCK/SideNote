package com.sidenote.app.data.settings

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                treeUri = preferences[TREE_URI]?.let(Uri::parse),
                voiceOnAtLaunch = preferences[VOICE_ON_AT_LAUNCH] ?: true,
                onlineFallbackAllowed = preferences[ONLINE_FALLBACK_ALLOWED] ?: false,
                onboardingComplete = preferences[ONBOARDING_COMPLETE] ?: false,
            )
        }

    override suspend fun setTreeUri(treeUri: Uri?) {
        dataStore.edit { preferences ->
            if (treeUri == null) {
                preferences.remove(TREE_URI)
            } else {
                preferences[TREE_URI] = treeUri.toString()
            }
        }
    }

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOICE_ON_AT_LAUNCH] = enabled
        }
    }

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) {
        dataStore.edit { preferences ->
            preferences[ONLINE_FALLBACK_ALLOWED] = allowed
        }
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETE] = complete
        }
    }

    private companion object {
        val TREE_URI = stringPreferencesKey("tree_uri")
        val VOICE_ON_AT_LAUNCH = booleanPreferencesKey("voice_on_at_launch")
        val ONLINE_FALLBACK_ALLOWED = booleanPreferencesKey("online_fallback_allowed")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
