package com.sidenote.app.data.settings

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setTreeUri(treeUri: Uri?)

    suspend fun setVoiceOnAtLaunch(enabled: Boolean)

    suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean)

    suspend fun setOnboardingComplete(complete: Boolean = true)
}
