package com.sidenote.app.review.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.MainActivity
import com.sidenote.app.MainDependencies
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.CaptureDependencies
import com.sidenote.app.capture.SpeechAvailability
import com.sidenote.app.capture.SpeechEngine
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var application: SideNoteApplication
    private lateinit var originalContainer: AppContainer
    private lateinit var container: NavigationContainer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun installContainer() {
        application = ApplicationProvider.getApplicationContext()
        originalContainer = application.container
        container = NavigationContainer()
        application.installContainerForTesting(container)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun restoreContainer() {
        scenario?.close()
        application.installContainerForTesting(originalContainer)
    }

    @Test
    fun configuredMainActivityRoutesFromReviewToSettingsAndPersistsVoiceToggle() {
        compose.onNodeWithText("Dates").assertIsDisplayed()
        compose.onNodeWithText("Production wiring").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Voice on at launch").assertIsOn().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            container.settings.value.voiceOnAtLaunch.not()
        }
        assertThat(container.settings.value.voiceOnAtLaunch).isFalse()
    }
}

private class NavigationContainer : AppContainer {
    val settings = NavigationSettingsRepository()
    private val repository = NavigationRepository()

    override fun captureDependencies(): CaptureDependencies = error("Capture is outside this test")

    override fun mainDependencies(): MainDependencies = MainDependencies(
        settings = settings,
        repository = repository,
        speechFactory = { NavigationSpeechEngine },
        ioDispatcher = Dispatchers.IO,
    )
}

private class NavigationSettingsRepository : SettingsRepository {
    private val mutableSettings = MutableStateFlow(
        AppSettings(
            treeUri = Uri.parse("content://notes/tree/SideNote"),
            voiceOnAtLaunch = true,
            voiceDisclosureAccepted = true,
            onboardingComplete = true,
        ),
    )
    val value: AppSettings get() = mutableSettings.value
    override val settings: Flow<AppSettings> = mutableSettings

    override suspend fun setTreeUri(treeUri: Uri?) {
        mutableSettings.value = value.copy(treeUri = treeUri)
    }

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) {
        mutableSettings.value = value.copy(voiceOnAtLaunch = enabled)
    }

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) {
        mutableSettings.value = value.copy(
            voiceDisclosureAccepted = true,
            onlineFallbackAllowed = allowed,
        )
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        mutableSettings.value = value.copy(onboardingComplete = complete)
    }
}

private class NavigationRepository : DocumentRepository {
    private val day = MarkdownCodec().parse(
        LocalDate.parse("2026-08-27"),
        "# 2026-08-27\n\n- [ ] **14:26** Production wiring\n",
    )

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult =
        error("Review navigation must not append")

    override suspend fun days(): List<ParsedDailyFile> = listOf(day)

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = UpdateResult.Success

    override suspend fun uncheckedCount(): Int = 1
}

private object NavigationSpeechEngine : SpeechEngine {
    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available
    override suspend fun requestModelDownloads() = Unit
    override fun start(listener: SpeechEngine.Listener) = Unit
    override fun stop() = Unit
    override fun destroy() = Unit
}
