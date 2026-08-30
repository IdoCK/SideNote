package com.sidenote.app.data.settings

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun defaultsArePrivacySafeAndVoiceOn() = runTest {
        val repo = repository(backgroundScope)

        assertThat(repo.settings.first()).isEqualTo(
            AppSettings(
                treeUri = null,
                voiceOnAtLaunch = true,
                onlineFallbackAllowed = false,
                onboardingComplete = false,
            ),
        )
    }

    @Test
    fun fallbackTurnsOnOnlyAfterDisclosureAcceptance() = runTest {
        val repo = repository(backgroundScope)

        repo.acceptVoiceDisclosureAndSetFallback(true)

        assertThat(repo.settings.first().onlineFallbackAllowed).isTrue()
    }

    @Test
    fun treeUriSettingIsPublished() = runTest {
        val repo = repository(backgroundScope)
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASideNote")

        repo.setTreeUri(uri)

        assertThat(repo.settings.first().treeUri).isEqualTo(uri)
    }

    @Test
    fun voiceDefaultSettingIsPublished() = runTest {
        val repo = repository(backgroundScope)

        repo.setVoiceOnAtLaunch(false)

        assertThat(repo.settings.first().voiceOnAtLaunch).isFalse()
    }

    @Test
    fun onboardingSettingIsPublished() = runTest {
        val repo = repository(backgroundScope)

        repo.setOnboardingComplete(true)

        assertThat(repo.settings.first().onboardingComplete).isTrue()
    }

    private fun repository(scope: CoroutineScope): SettingsRepository {
        val file = Files.createTempFile("sidenote-settings", ".preferences_pb").toFile()
        file.delete()
        temporaryFiles += file
        return DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
