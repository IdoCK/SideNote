package com.sidenote.app.notification

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.MainActivity
import com.sidenote.app.MainDependencies
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.CaptureActivity
import com.sidenote.app.capture.CaptureDependencies
import com.sidenote.app.capture.CaptureRecoveryHandoff
import com.sidenote.app.capture.SpeechAvailability
import com.sidenote.app.capture.SpeechEngine
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import com.sidenote.app.privacy.LockState
import com.sidenote.app.privacy.AndroidLockState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewUnlockRoutingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val application = ApplicationProvider.getApplicationContext<SideNoteApplication>()
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalContainer: AppContainer
    private var container: UnlockRoutingContainer? = null
    private var mainScenario: ActivityScenario<MainActivity>? = null
    private var captureScenario: ActivityScenario<CaptureActivity>? = null

    @Before
    fun rememberContainer() {
        originalContainer = application.container
    }

    @After
    fun restoreContainer() {
        captureScenario?.close()
        mainScenario?.close()
        container?.close()
        application.installContainerForTesting(originalContainer)
    }

    @Test
    fun lockedNotificationIntentLoadsNothingThenRoutesOnceAfterUnlockAcrossRecreationAndRepeats() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = true,
            settings = configuredSettings(),
            days = listOf(
                day("2026-08-27", "- [ ] **09:00** most recent unchecked"),
                day("2026-08-28", "- [x] **10:00** newer processed note"),
            ),
        ).also(::install)
        val intent = Intent(targetContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED, true)

        mainScenario = ActivityScenario.launch(intent)
        compose.onNodeWithText("Unlock SideNote").assertIsDisplayed()
        compose.onNodeWithText("most recent unchecked").assertDoesNotExist()
        compose.onNodeWithText("newer processed note").assertDoesNotExist()
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(0)
        assertThat(fake.settings.reads.get()).isEqualTo(0)
        assertThat(fake.repository.daysCalls.get()).isEqualTo(0)
        assertThat(fake.notificationScheduler.enqueueCalls.get()).isEqualTo(0)

        mainScenario?.recreate()
        repeat(2) {
            targetContext.startActivity(
                Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) { fake.lockState.dismissRequests.get() >= 2 }
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(0)
        assertThat(fake.repository.daysCalls.get()).isEqualTo(0)

        fake.lockState.unlock()

        compose.waitUntil(timeoutMillis = 5_000) {
            fake.repository.daysCalls.get() == 1 && fake.settings.reads.get() >= 1
        }
        compose.onNodeWithText("most recent unchecked").assertIsDisplayed()
        compose.onNodeWithText("newer processed note").assertDoesNotExist()
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(1)
        assertThat(fake.repository.daysCalls.get()).isEqualTo(1)
        assertThat(fake.notificationScheduler.enqueueCalls.get()).isEqualTo(1)
    }

    @Test
    fun cancelledOrFailedDismissalRemainsOnGenericGateWithoutDataAccess() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = true,
            settings = configuredSettings(),
            days = listOf(day("2026-08-27", "- [ ] **09:00** private note")),
        ).also(::install)

        mainScenario = ActivityScenario.launch(
            Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED, true),
        )
        compose.waitUntil(timeoutMillis = 5_000) { fake.lockState.dismissRequests.get() == 1 }
        fake.lockState.dismissalDidNotUnlock()

        compose.onNodeWithText("Unlock SideNote").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { fake.lockState.dismissRequests.get() == 2 }
        compose.onNodeWithText("private note").assertDoesNotExist()
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(0)
        assertThat(fake.settings.reads.get()).isEqualTo(0)
        assertThat(fake.repository.daysCalls.get()).isEqualTo(0)
    }

    @Test
    fun reusedMainCommitsAnOpaqueGateBeforeHandlingALockedNotificationIntent() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = false,
            settings = configuredSettings(),
            days = listOf(day("2026-08-27", "- [ ] **09:00** private reused frame")),
        ).also(::install)
        mainScenario = ActivityScenario.launch(Intent(targetContext, MainActivity::class.java))
        compose.onNodeWithText("private reused frame").assertIsDisplayed()
        val initialRecoveryEnqueues = fake.notificationScheduler.enqueueCalls.get()

        fake.lockState.relockWithoutPublishing()
        targetContext.startActivity(
            Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )

        compose.onNodeWithText("Unlock SideNote").assertIsDisplayed()
        compose.onNodeWithText("private reused frame").assertDoesNotExist()
        assertThat(fake.lockState.dismissRequests.get()).isEqualTo(1)

        fake.lockState.unlock()
        compose.onNodeWithText("private reused frame").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) {
            fake.notificationScheduler.enqueueCalls.get() == initialRecoveryEnqueues + 1
        }
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(1)
    }

    @Test
    fun warmCaptureReconcilesAfterTheProcessWasFirstUsedWhileLocked() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = true,
            settings = configuredSettings(),
            days = emptyList(),
        ).also(::install)
        val intent = Intent(targetContext, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        captureScenario = ActivityScenario.launch(intent)
        compose.waitUntil(timeoutMillis = 5_000) { fake.recovery.loads.get() == 1 }
        assertThat(fake.notificationScheduler.enqueueCalls.get()).isEqualTo(0)
        captureScenario?.close()

        fake.lockState.unlock()
        captureScenario = ActivityScenario.launch(intent)

        compose.waitUntil(timeoutMillis = 5_000) {
            fake.notificationScheduler.enqueueCalls.get() == 1
        }
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(0)
    }

    @Test
    fun externalNotificationPermissionRestoreReconcilesWhenExistingMainResumes() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = false,
            settings = configuredSettings(),
            days = emptyList(),
        ).also(::install)
        val packageName = targetContext.packageName
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        // connectedDebugAndroidTest installs a fresh APK and removes it after the run.
        // Revoking our own permission here would kill the instrumentation process.
        assertThat(manager.areNotificationsEnabled()).isFalse()
        mainScenario = ActivityScenario.launch(Intent(targetContext, MainActivity::class.java))
        compose.waitUntil(timeoutMillis = 5_000) { fake.repository.daysCalls.get() == 1 }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Notifications: Not allowed").performScrollTo().assertIsDisplayed()
        val initialRecoveryEnqueues = fake.notificationScheduler.enqueueCalls.get()
        mainScenario?.moveToState(Lifecycle.State.CREATED)

        executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        waitUntil(timeoutMillis = 5_000) { manager.areNotificationsEnabled() }
        mainScenario?.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithText("Notifications: Allowed").performScrollTo().assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) {
            fake.notificationScheduler.enqueueCalls.get() == initialRecoveryEnqueues + 1
        }
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(1)
    }

    @Test
    fun realAdapterReportsTheActualCredentialFreeKeyguardAsLocked() {
        val wasDisabled = shellOutput("locksettings get-disabled").trim() == "true"
        try {
            if (wasDisabled) executeShellCommand("locksettings set-disabled false")
            executeShellCommand("input keyevent KEYCODE_SLEEP")
            waitUntil(timeoutMillis = 5_000) { keyguardManager().isKeyguardLocked }

            val realLockState = AndroidLockState(targetContext)
            realLockState.refresh()

            assertThat(realLockState.locked.value).isTrue()
        } finally {
            executeShellCommand("input keyevent KEYCODE_WAKEUP")
            executeShellCommand("wm dismiss-keyguard")
            if (wasDisabled) executeShellCommand("locksettings set-disabled true")
        }
    }

    @Test
    fun incompleteCaptureLoadsNoRecoveryOrFolderDetailsUntilUnlockThenHandsOffToOnboarding() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = true,
            settings = configuredSettings().copy(
                treeUri = Uri.parse("content://private/tree/SecretFolder"),
                voiceDisclosureAccepted = false,
                onboardingComplete = false,
            ),
            days = emptyList(),
            recoveryText = "highly private recovery draft",
        ).also(::install)

        captureScenario = ActivityScenario.launch(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        compose.waitUntil(timeoutMillis = 5_000) { fake.lockState.dismissRequests.get() == 1 }
        compose.onNodeWithText("Unlock SideNote").assertIsDisplayed()
        compose.onNodeWithText("highly private recovery draft").assertDoesNotExist()
        compose.onNodeWithText("SecretFolder", substring = true).assertDoesNotExist()
        assertThat(fake.recovery.loads.get()).isEqualTo(0)
        assertThat(fake.mainDependenciesCalls.get()).isEqualTo(0)

        fake.lockState.unlock()

        compose.waitUntil(timeoutMillis = 5_000) { fake.mainDependenciesCalls.get() == 1 }
        compose.onNodeWithText("Allow voice capture and reminders").assertIsDisplayed()
        assertThat(fake.recovery.loads.get()).isEqualTo(0)
    }

    @Test
    fun completedOnboardingPreservesRecoveryCaptureSurfaceWhileLocked() {
        val fake = UnlockRoutingContainer(
            initiallyLocked = true,
            settings = configuredSettings(),
            days = emptyList(),
            recoveryText = "lock-screen capture allowed",
        ).also(::install)

        captureScenario = ActivityScenario.launch(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        compose.waitUntil(timeoutMillis = 5_000) { fake.recovery.loads.get() == 1 }
        compose.onNodeWithText("lock-screen capture allowed").assertIsDisplayed()
        assertThat(fake.lockState.dismissRequests.get()).isEqualTo(0)
    }

    private fun install(fake: UnlockRoutingContainer) {
        container = fake
        application.installContainerForTesting(fake)
    }

    private fun configuredSettings() = AppSettings(
        treeUri = Uri.parse("content://notes/tree/SideNote"),
        voiceOnAtLaunch = false,
        voiceDisclosureAccepted = true,
        onboardingComplete = true,
    )

    private fun day(date: String, task: String): ParsedDailyFile {
        val localDate = LocalDate.parse(date)
        return MarkdownCodec().parse(localDate, "# $date\n\n$task\n")
    }

    private fun keyguardManager(): KeyguardManager =
        targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

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

private class UnlockRoutingContainer(
    initiallyLocked: Boolean,
    settings: AppSettings,
    days: List<ParsedDailyFile>,
    recoveryText: String = "unused recovery draft",
) : AppContainer {
    val lockState = FakeLockState(initiallyLocked)
    val settings = CountingSettingsRepository(settings)
    val repository = CountingDocumentRepository(days)
    val recovery = CountingRecoveryStore(recoveryText)
    val notificationScheduler = RecordingNotificationRefreshScheduler()
    val mainDependenciesCalls = AtomicInteger()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryHandoff = CaptureRecoveryHandoff(recovery, processScope)

    override fun lockState(): LockState = lockState

    override fun notificationRefreshScheduler(): NotificationRefreshScheduler =
        notificationScheduler

    override fun mainDependencies(): MainDependencies {
        mainDependenciesCalls.incrementAndGet()
        return MainDependencies(
            settings = settings,
            repository = repository,
            speechFactory = { NoOpSpeechEngine },
            ioDispatcher = Dispatchers.IO,
        )
    }

    override fun captureDependencies(): CaptureDependencies = CaptureDependencies(
        settings = settings,
        repository = repository,
        speechFactory = { NoOpSpeechEngine },
        completionSignals = emptyFlow(),
        clock = Clock.fixed(
            Instant.parse("2026-08-27T13:00:00Z"),
            ZoneId.of("America/New_York"),
        ),
        zone = ZoneId.of("America/New_York"),
        ioDispatcher = Dispatchers.IO,
        recoveryHandoff = recoveryHandoff,
    )

    fun close() {
        processScope.cancel()
    }
}

private class FakeLockState(initiallyLocked: Boolean) : LockState {
    private val systemLocked = AtomicBoolean(initiallyLocked)
    private val mutableLocked = MutableStateFlow(initiallyLocked)
    override val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()
    val dismissRequests = AtomicInteger()

    override fun refresh() {
        mutableLocked.value = systemLocked.get()
    }

    override fun requestDismissKeyguard(activity: Activity) {
        dismissRequests.incrementAndGet()
    }

    fun unlock() {
        systemLocked.set(false)
        mutableLocked.value = false
    }

    fun relockWithoutPublishing() {
        systemLocked.set(true)
    }

    fun dismissalDidNotUnlock() {
        systemLocked.set(true)
        mutableLocked.value = true
    }
}

private class RecordingNotificationRefreshScheduler : NotificationRefreshScheduler {
    val enqueueCalls = AtomicInteger()

    override fun enqueue() {
        enqueueCalls.incrementAndGet()
    }
}

private class CountingSettingsRepository(initial: AppSettings) : SettingsRepository {
    private val mutableSettings = MutableStateFlow(initial)
    val reads = AtomicInteger()
    override val settings: Flow<AppSettings> = mutableSettings.onStart { reads.incrementAndGet() }

    override suspend fun setTreeUri(treeUri: Uri?) {
        mutableSettings.value = mutableSettings.value.copy(treeUri = treeUri)
    }

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(voiceOnAtLaunch = enabled)
    }

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(
            voiceDisclosureAccepted = true,
            onlineFallbackAllowed = allowed,
        )
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(onboardingComplete = complete)
    }
}

private class CountingDocumentRepository(
    private val parsedDays: List<ParsedDailyFile>,
) : DocumentRepository {
    val daysCalls = AtomicInteger()

    override suspend fun days(): List<ParsedDailyFile> {
        daysCalls.incrementAndGet()
        return parsedDays
    }

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult =
        AppendResult.Success

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = UpdateResult.Success

    override suspend fun uncheckedCount(): Int =
        parsedDays.sumOf { day -> day.entries.count { entry -> !entry.processed } }
}

private class CountingRecoveryStore(recoveryText: String) : RecoveryDraftStore {
    private val draft = RecoveryDraft(recoveryText, TextRange(recoveryText.length), false)
    val loads = AtomicInteger()

    override suspend fun load(): RecoveryLoadResult {
        loads.incrementAndGet()
        return RecoveryLoadResult.Draft(draft)
    }

    override suspend fun save(draft: RecoveryDraft) = Unit

    override suspend fun clear() = Unit
}

private object NoOpSpeechEngine : SpeechEngine {
    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available
    override suspend fun requestModelDownloads() = Unit
    override fun start(listener: SpeechEngine.Listener) = Unit
    override fun stop() = Unit
    override fun destroy() = Unit
}
