package com.sidenote.app.capture

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<SideNoteApplication>()
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalContainer: AppContainer
    private lateinit var container: FakeCaptureAppContainer
    private var scenario: ActivityScenario<CaptureActivity>? = null
    private var lockScreenWasDisabled = false

    @Before
    fun installFakeContainer() {
        originalContainer = application.container
        container = FakeCaptureAppContainer()
        application.installContainerForTesting(container)
    }

    @After
    fun restoreApplicationAndDevice() {
        scenario?.close()
        application.installContainerForTesting(originalContainer)
        executeShellCommand("input keyevent KEYCODE_WAKEUP")
        executeShellCommand("wm dismiss-keyguard")
        if (lockScreenWasDisabled) {
            executeShellCommand("locksettings set-disabled true")
        }
    }

    @Test
    fun secondSingleTopLaunchCompletesExactlyOnce() {
        launchCapture()

        targetContext.startActivity(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        compose.waitUntil(timeoutMillis = 5_000) {
            container.repository.appends.size == 1
        }
        assertThat(container.repository.appends).containsExactly("captured thought")
        assertRecoveryFlushAppendAndClearOrder()
    }

    @Test
    fun screenOffSignalWhileCaptureIsActiveCompletesExactlyOnce() {
        launchCapture()

        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()

        compose.waitUntil(timeoutMillis = 5_000) {
            container.repository.appends.size == 1
        }
        assertThat(container.repository.appends).containsExactly("captured thought")
        assertRecoveryFlushAppendAndClearOrder()
    }

    @Test
    fun setupBackgroundFlushesRecoveryWithoutCompleting() {
        container.markSetupIncomplete()
        launchCapture()

        scenario?.moveToState(Lifecycle.State.CREATED)

        compose.waitUntil(timeoutMillis = 5_000) {
            container.events.lastOrNull() == "save"
        }
        assertThat(container.events).containsExactly("load", "save").inOrder()
        assertThat(container.repository.appends).isEmpty()
    }

    @Test
    fun lockedCaptureHasNoReviewSettingsDateProjectOrHistorySemantics() {
        lockScreenWasDisabled = shellOutput("locksettings get-disabled").trim() == "true"
        if (lockScreenWasDisabled) {
            executeShellCommand("locksettings set-disabled false")
        }
        executeShellCommand("input keyevent KEYCODE_SLEEP")
        waitUntil(timeoutMillis = 5_000) { keyguardManager().isKeyguardLocked }

        launchCapture()

        waitUntil(timeoutMillis = 5_000) { keyguardManager().isKeyguardLocked }
        listOf("Review", "Settings", "Date", "Project", "History").forEach { privateLabel ->
            compose.onNodeWithText(privateLabel, substring = true).assertDoesNotExist()
            compose.onNodeWithContentDescription(privateLabel, substring = true).assertDoesNotExist()
        }
    }

    private fun launchCapture() {
        scenario = ActivityScenario.launch(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitForIdle()
    }

    private fun keyguardManager(): KeyguardManager =
        targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private fun assertRecoveryFlushAppendAndClearOrder() {
        compose.waitUntil(timeoutMillis = 5_000) {
            container.events.lastOrNull() == "clear"
        }
        assertThat(container.events).containsExactly(
            "load",
            "save",
            "append",
            "clear",
        ).inOrder()
    }

    private fun executeShellCommand(command: String) {
        shellOutput(command)
    }

    private fun shellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { reader -> reader.readText() }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertThat(condition()).isTrue()
    }
}

private class FakeCaptureAppContainer : AppContainer {
    val events = CopyOnWriteArrayList<String>()
    val repository = RecordingCaptureRepository(events)
    val completionSignals = FakeCompletionSignals()
    private val recovery = FakeRecoveryDraftStore(events)
    private val settings = FakeSettingsRepository()

    fun markSetupIncomplete() {
        settings.settings.value = settings.settings.value.copy(
            treeUri = null,
            onboardingComplete = false,
        )
    }

    override fun captureDependencies(): CaptureDependencies = CaptureDependencies(
        settings = settings,
        recovery = recovery,
        repository = repository,
        speechFactory = { FakeSpeechEngine() },
        completionSignals = completionSignals.signals,
        clock = Clock.fixed(
            Instant.parse("2026-08-30T17:00:00Z"),
            ZoneId.of("America/New_York"),
        ),
        zone = ZoneId.of("America/New_York"),
        ioDispatcher = Dispatchers.Unconfined,
    )
}

private class FakeCompletionSignals {
    private val mutableSignals = MutableSharedFlow<CompletionSignal>(extraBufferCapacity = 4)
    val signals: Flow<CompletionSignal> = mutableSignals

    fun simulate(action: String): Boolean =
        action == Intent.ACTION_SCREEN_OFF && mutableSignals.tryEmit(CompletionSignal.ScreenOff)
}

private class FakeSettingsRepository : SettingsRepository {
    override val settings = MutableStateFlow(
        AppSettings(
            treeUri = Uri.parse("content://com.sidenote.app.test.documents/root"),
            voiceOnAtLaunch = false,
            onboardingComplete = true,
        ),
    )

    override suspend fun setTreeUri(treeUri: Uri?) = Unit

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) = Unit

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) = Unit

    override suspend fun setOnboardingComplete(complete: Boolean) = Unit
}

private class FakeRecoveryDraftStore(
    private val events: MutableList<String>,
) : RecoveryDraftStore {
    override suspend fun load(): RecoveryLoadResult {
        events += "load"
        return RecoveryLoadResult.Draft(
            RecoveryDraft(
                text = "captured thought",
                selection = TextRange(16),
                voiceEnabled = false,
            ),
        )
    }

    override suspend fun save(draft: RecoveryDraft) {
        events += "save"
    }

    override suspend fun clear() {
        events += "clear"
    }
}

private class RecordingCaptureRepository(
    private val events: MutableList<String>,
) : DocumentRepository {
    val appends = CopyOnWriteArrayList<String>()

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult {
        events += "append"
        appends += text
        return AppendResult.Success
    }

    override suspend fun days(): List<ParsedDailyFile> = emptyList()

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Not used by CaptureActivityTest")

    override suspend fun uncheckedCount(): Int = 0
}

private class FakeSpeechEngine : SpeechEngine {
    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available

    override suspend fun requestModelDownloads() = Unit

    override fun start(listener: SpeechEngine.Listener) = Unit

    override fun stop() = Unit

    override fun destroy() = Unit
}
