package com.sidenote.app.capture

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import com.sidenote.app.capture.ui.CAPTURE_INPUT_TAG
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.MainActivity
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
import com.sidenote.app.notification.NotificationRefresher
import com.sidenote.app.notification.BackgroundNotificationRefresher
import com.sidenote.app.notification.NotificationRefreshResult
import com.sidenote.app.notification.UnavailableNotificationRefresher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
    private var recreatedCapture: CaptureActivity? = null
    private var lockScreenWasDisabled = false

    @Before
    fun installFakeContainer() {
        originalContainer = application.container
        container = FakeCaptureAppContainer()
        application.installContainerForTesting(container)
    }

    @After
    fun restoreApplicationAndDevice() {
        recreatedCapture?.let { activity ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (!activity.isFinishing) activity.finish()
            }
        }
        scenario?.close()
        container.close()
        application.installContainerForTesting(originalContainer)
        executeShellCommand("input keyevent KEYCODE_WAKEUP")
        executeShellCommand("wm dismiss-keyguard")
        if (lockScreenWasDisabled) {
            executeShellCommand("locksettings set-disabled true")
        }
    }

    @Test
    fun secondSingleTopLaunchKeepsCaptureOpenWithoutSaving() {
        launchCapture()

        targetContext.startActivity(
            Intent(targetContext, CaptureActivity::class.java)
                .putExtra("repeat_delivered", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        waitUntil(timeoutMillis = 5_000) {
            var delivered = false
            scenario?.onActivity { delivered = it.intent.getBooleanExtra("repeat_delivered", false) }
            delivered
        }
        compose.waitForIdle()
        assertThat(container.repository.appends).isEmpty()
        scenario?.onActivity { assertThat(it.isFinishing).isFalse() }
        compose.onNodeWithText("captured thought").fetchSemanticsNode()
    }

    @Test
    fun launchWhileSavingOpensFreshCaptureAfterCommitInsteadOfClosingTheNewRequest() {
        val pending = container.repository.suspendNextAppend()
        launchCapture()
        lateinit var original: CaptureActivity
        scenario?.onActivity { original = it }
        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        compose.waitUntil(timeoutMillis = 5_000) { pending.started.isCompleted }
        targetContext.startActivity(Intent(targetContext, CaptureActivity::class.java)
            .putExtra("rapid_launch", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil(timeoutMillis = 5_000) {
            original.intent.getBooleanExtra("rapid_launch", false)
        }
        pending.release.complete(Unit)
        waitUntil(timeoutMillis = 5_000) {
            liveCaptureActivities().any { it !== original && !it.isFinishing }
        }
        recreatedCapture = liveCaptureActivities().first { it !== original && !it.isFinishing }
        waitForCaptureResumed(recreatedCapture!!)
        assertThat(container.repository.appends).containsExactly("captured thought")
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).assertTextEquals("")
    }

    @Test
    fun committedCaptureClosesWithoutWaitingForNotificationRefreshWhichSurvivesItsHost() {
        val suspendedRefresh = SuspendedTestOperation()
        container.notificationRefresher = NotificationRefresher {
            suspendedRefresh.started.complete(Unit)
            try {
                suspendedRefresh.release.await()
                suspendedRefresh.completed.complete(Unit)
                NotificationRefreshResult.Removed
            } catch (error: CancellationException) {
                suspendedRefresh.cancelled.complete(Unit)
                throw error
            }
        }
        launchCapture()
        lateinit var activity: CaptureActivity
        scenario?.onActivity { activity = it }

        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        compose.waitUntil(timeoutMillis = 5_000) { suspendedRefresh.started.isCompleted }

        assertThat(container.events).contains("clear")
        waitUntil(timeoutMillis = 5_000) { activity.isDestroyed }
        assertThat(suspendedRefresh.cancelled.isCompleted).isFalse()
        suspendedRefresh.release.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { suspendedRefresh.completed.isCompleted }
        assertThat(suspendedRefresh.completed.isCompleted).isTrue()
        assertThat(suspendedRefresh.cancelled.isCompleted).isFalse()
        assertThat(container.repository.appends).containsExactly("captured thought")
    }

    @Test
    fun preflightCompletionSignalsCoalesceAndSaveOnceAfterConfiguredInitialization() {
        val suspendedRead = container.suspendNextSettingsRead()
        scenario = ActivityScenario.launch(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitUntil(timeoutMillis = 5_000) { suspendedRead.started.isCompleted }

        targetContext.startActivity(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        scenario?.moveToState(Lifecycle.State.CREATED)
        suspendedRead.release.complete(Unit)

        compose.waitUntil(timeoutMillis = 5_000) { container.repository.appends.size == 1 }
        compose.waitForIdle()
        assertThat(container.repository.appends).containsExactly("captured thought")
        assertThat(container.events.count { event -> event == "append" }).isEqualTo(1)
        assertRecoveryFlushAppendAndClearOrder()
    }

    @Test
    fun preflightRepeatedLaunchSurvivesRecreationWithoutSavingUntilScreenOff() {
        val suspendedRead = container.suspendNextSettingsRead()
        val suspendedAppend = container.repository.suspendNextAppend()
        launchCapture()
        compose.waitUntil(timeoutMillis = 5_000) { suspendedRead.started.isCompleted }
        targetContext.startActivity(
            Intent(targetContext, CaptureActivity::class.java)
                .putExtra("preflight_repeat_delivered", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitUntil(timeoutMillis = 5_000) {
            var delivered = false
            scenario?.onActivity { activity ->
                delivered = activity.intent.getBooleanExtra("preflight_repeat_delivered", false)
            }
            delivered
        }

        scenario?.recreate()

        compose.waitForIdle()
        assertThat(suspendedAppend.started.isCompleted).isFalse()
        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        compose.waitUntil(timeoutMillis = 5_000) { suspendedAppend.started.isCompleted }
        suspendedAppend.release.complete(Unit)
        assertThat(suspendedRead.cancelled.isCompleted).isTrue()
        assertThat(container.settingsReads.get()).isEqualTo(2)
        assertThat(container.repository.appends).containsExactly("captured thought")
        assertRecoveryFlushAppendAndClearOrder()
    }

    @Test
    fun preflightScreenOffAloneCompletesAfterInitialization() {
        val suspendedRead = container.suspendNextSettingsRead()
        launchCapture()
        compose.waitUntil(timeoutMillis = 5_000) { suspendedRead.started.isCompleted }

        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        compose.waitForIdle()
        suspendedRead.release.complete(Unit)

        compose.waitUntil(timeoutMillis = 5_000) { container.repository.appends.size == 1 }
        assertRecoveryFlushAppendAndClearOrder()
    }

    @Test
    fun preflightBackgroundAloneCompletesAfterInitialization() {
        val suspendedRead = container.suspendNextSettingsRead()
        launchCapture()
        compose.waitUntil(timeoutMillis = 5_000) { suspendedRead.started.isCompleted }

        scenario?.moveToState(Lifecycle.State.CREATED)
        suspendedRead.release.complete(Unit)

        compose.waitUntil(timeoutMillis = 5_000) { container.repository.appends.size == 1 }
        assertRecoveryFlushAppendAndClearOrder()
    }

    @Test
    fun preflightCompletionSignalsAreDiscardedWhenOnboardingIsIncomplete() {
        container.markOnboardingIncomplete()
        val suspendedRead = container.suspendNextSettingsRead()
        scenario = ActivityScenario.launch(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitUntil(timeoutMillis = 5_000) { suspendedRead.started.isCompleted }

        targetContext.startActivity(
            Intent(targetContext, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        suspendedRead.release.complete(Unit)

        compose.waitUntil(timeoutMillis = 5_000) { container.settingsReads.get() == 1 }
        compose.waitForIdle()
        assertThat(container.repository.appends).isEmpty()
        assertThat(container.recovery.loads.get()).isEqualTo(0)
        assertThat(container.speechFactoryCalls.get()).isEqualTo(0)
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
    fun voiceEnabledSessionStartsOnceAndNeverRestartsWhileSavingOrTerminal() {
        container.enableVoice()
        val suspendedLoad = container.recovery.suspendNextLoad()
        val suspendedAppend = container.repository.suspendNextAppend()
        launchCapture()

        compose.waitUntil(timeoutMillis = 5_000) { suspendedLoad.started.isCompleted }
        suspendedLoad.release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.speechEngine.startCalls.get() >= 1
        }
        compose.waitForIdle()
        assertThat(container.speechEngine.startCalls.get()).isEqualTo(1)

        assertThat(container.completionSignals.simulate(Intent.ACTION_SCREEN_OFF)).isTrue()
        compose.waitUntil(timeoutMillis = 5_000) { suspendedAppend.started.isCompleted }
        scenario?.moveToState(Lifecycle.State.CREATED)
        scenario?.moveToState(Lifecycle.State.RESUMED)

        assertThat(container.speechEngine.startCalls.get()).isEqualTo(1)
        suspendedAppend.release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.events.lastOrNull() == "clear"
        }
        compose.waitForIdle()
        assertThat(container.speechEngine.startCalls.get()).isEqualTo(1)
    }

    @Test
    fun startStopStartBeforeReadinessKeepsOnlyLatestSpeechStartupWaiter() {
        container.enableVoice()
        container.markFolderUnavailable()
        val suspendedLoad = container.recovery.suspendNextLoad()
        launchCapture()

        compose.waitUntil(timeoutMillis = 5_000) { suspendedLoad.started.isCompleted }
        scenario?.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.completionSignals.activeCollectors.get() == 0
        }
        scenario?.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.completionSignals.activeCollectors.get() == 1
        }

        suspendedLoad.release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.speechFactoryCalls.get() == 1 &&
                container.speechEngine.startCalls.get() >= 1
        }
        compose.waitForIdle()

        assertThat(container.speechEngine.startCalls.get()).isEqualTo(1)
    }

    @Test
    fun stoppedBeforeReadinessNeverStartsSpeech() {
        container.enableVoice()
        container.markFolderUnavailable()
        val suspendedLoad = container.recovery.suspendNextLoad()
        launchCapture()

        compose.waitUntil(timeoutMillis = 5_000) { suspendedLoad.started.isCompleted }
        scenario?.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.completionSignals.activeCollectors.get() == 0
        }
        suspendedLoad.release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            container.speechFactoryCalls.get() == 1
        }
        compose.waitForIdle()

        assertThat(container.speechEngine.startCalls.get()).isEqualTo(0)
    }

    @Test
    fun finishingStopFlushSurvivesViewModelClearing() {
        val suspendedSave = container.recovery.suspendNextSave()
        launchCapture()

        scenario?.onActivity(CaptureActivity::finish)

        compose.waitUntil(timeoutMillis = 5_000) { suspendedSave.started.isCompleted }
        assertThat(suspendedSave.cancelled.isCompleted).isFalse()
        suspendedSave.release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { suspendedSave.completed.isCompleted }
        assertThat(container.repository.appends).isEmpty()
        assertThat(container.events).doesNotContain("clear")
        assertThat(container.recovery.mainThreadCalls.get()).isEqualTo(0)
    }

    @Test
    fun externalSetupRoundTripSuppressesBackgroundSaveThenRearms() {
        launchCapture()

        scenario?.onActivity { activity ->
            assertThat(
                activity.launchExternalSetup(Intent(activity, MainActivity::class.java)),
            ).isTrue()
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            scenario?.state == Lifecycle.State.CREATED &&
                container.events.contains("save") &&
                container.completionSignals.activeCollectors.get() == 0
        }
        assertThat(container.repository.appends).isEmpty()

        finishExternalSetup()
        waitForCaptureResumed()
        compose.waitForIdle()
        scenario?.moveToState(Lifecycle.State.CREATED)

        compose.waitUntil(timeoutMillis = 5_000) {
            container.repository.appends.size == 1
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            container.completionSignals.activeCollectors.get() == 0
        }
        assertThat(container.repository.appends).containsExactly("captured thought")
    }

    @Test
    fun doubleExternalSetupLaunchRejectsSecondWithoutRearmingFirst() {
        launchCapture()

        scenario?.onActivity { activity ->
            assertThat(
                activity.launchExternalSetup(Intent(activity, MainActivity::class.java)),
            ).isTrue()
            val missingDestination = Intent().setClassName(
                activity,
                "com.sidenote.app.missing.MissingSetupActivity",
            )
            assertThat(activity.launchExternalSetup(missingDestination)).isFalse()
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            scenario?.state == Lifecycle.State.CREATED &&
                container.events.contains("save") &&
                container.completionSignals.activeCollectors.get() == 0
        }
        assertThat(container.repository.appends).isEmpty()

        finishExternalSetup()
        waitForCaptureResumed()
    }

    @Test
    fun externalSetupStateSurvivesCaptureRecreationUntilOriginalResult() {
        launchCapture()
        compose.waitUntil(timeoutMillis = 5_000) { container.settingsReads.get() == 1 }

        scenario?.onActivity { activity ->
            assertThat(
                activity.launchExternalSetup(Intent(activity, MainActivity::class.java)),
            ).isTrue()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            captureScenarioState() == Lifecycle.State.CREATED &&
                container.events.contains("save") &&
                container.completionSignals.activeCollectors.get() == 0
        }

        val replacement = recreateStoppedCapture()
        var replacementAcceptedSecondLaunch = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            replacementAcceptedSecondLaunch = replacement.launchExternalSetup(
                Intent(replacement, MainActivity::class.java),
            )
        }

        if (replacementAcceptedSecondLaunch) {
            finishExternalSetup()
        }
        finishExternalSetup()
        waitForCaptureResumed(replacement)
        compose.waitUntil(timeoutMillis = 5_000) { container.settingsReads.get() >= 2 }

        assertThat(container.settingsReads.get()).isEqualTo(2)
        assertThat(container.repository.appends).isEmpty()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertThat(replacement.moveTaskToBack(true)).isTrue()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            container.repository.appends.size == 1
        }

        assertThat(replacementAcceptedSecondLaunch).isFalse()
        assertThat(container.repository.appends).containsExactly("captured thought")
    }

    @Test
    fun missingFolderBackgroundFlushesRecoveryWithoutCompleting() {
        container.markFolderUnavailable()
        launchCapture()

        scenario?.moveToState(Lifecycle.State.CREATED)

        compose.waitUntil(timeoutMillis = 5_000) {
            container.events.lastOrNull() == "save" &&
                container.completionSignals.activeCollectors.get() == 0
        }
        assertThat(container.events).containsExactly("load", "save").inOrder()
        assertThat(container.repository.appends).isEmpty()
        assertThat(container.recovery.mainThreadCalls.get()).isEqualTo(0)
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
        listOf(
            "Review",
            "Settings",
            "Date",
            "Project",
            "Folder",
            "Existing note",
            "Previous day",
            "History",
        ).forEach { privateLabel ->
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
        compose.waitUntil(timeoutMillis = 5_000) {
            container.completionSignals.activeCollectors.get() == 1
        }
    }

    private fun keyguardManager(): KeyguardManager =
        targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private fun isExternalSetupResumed(): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .any { activity -> activity is MainActivity }
        }
        return resumed
    }

    private fun finishExternalSetup() {
        waitUntil(timeoutMillis = 5_000, condition = ::isExternalSetupResumed)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val externalSetup = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .single()
            externalSetup.finish()
        }
    }

    private fun recreateStoppedCapture(): CaptureActivity {
        val original = liveCaptureActivities().single()
        InstrumentationRegistry.getInstrumentation().runOnMainSync(original::recreate)

        var replacement: CaptureActivity? = null
        waitUntil(timeoutMillis = 5_000) {
            replacement = liveCaptureActivities().singleOrNull { activity -> activity !== original }
            replacement != null
        }
        return checkNotNull(replacement).also { activity -> recreatedCapture = activity }
    }

    private fun liveCaptureActivities(): List<CaptureActivity> {
        var activities = emptyList<CaptureActivity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            activities = listOf(
                Stage.CREATED,
                Stage.STARTED,
                Stage.RESUMED,
                Stage.PAUSED,
                Stage.STOPPED,
            ).flatMap { stage -> monitor.getActivitiesInStage(stage) }
                .filterIsInstance<CaptureActivity>()
                .filter { activity -> !activity.isDestroyed }
        }
        return activities
    }

    private fun waitForCaptureResumed() {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (
            (captureScenarioState() != Lifecycle.State.RESUMED ||
                container.completionSignals.activeCollectors.get() != 1) &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(50)
        }
        assertThat(captureScenarioState()).isEqualTo(Lifecycle.State.RESUMED)
        assertThat(container.completionSignals.activeCollectors.get()).isEqualTo(1)
    }

    private fun waitForCaptureResumed(activity: CaptureActivity) {
        waitUntil(timeoutMillis = 5_000) {
            activityInStage(activity, Stage.RESUMED) &&
                container.completionSignals.activeCollectors.get() == 1
        }
    }

    private fun activityInStage(activity: CaptureActivity, stage: Stage): Boolean {
        var matches = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            matches = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(stage)
                .any { candidate -> candidate === activity }
        }
        return matches
    }

    private fun captureScenarioState(): Lifecycle.State? =
        runCatching { scenario?.state }.getOrNull()

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
        assertThat(container.recovery.mainThreadCalls.get()).isEqualTo(0)
        assertThat(container.repository.mainThreadCalls.get()).isEqualTo(0)
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
    val recovery = FakeRecoveryDraftStore(events)
    private val settings = FakeSettingsRepository()
    val speechEngine = FakeSpeechEngine()
    val speechFactoryCalls = AtomicInteger()
    val settingsReads: AtomicInteger get() = settings.reads
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryHandoff = CaptureRecoveryHandoff(recovery, processScope)
    var notificationRefresher: NotificationRefresher = UnavailableNotificationRefresher

    fun enableVoice() {
        recovery.draft = recovery.draft.copy(voiceEnabled = true)
    }

    fun markFolderUnavailable() {
        settings.markFolderUnavailable()
    }

    fun markOnboardingIncomplete() {
        settings.markOnboardingIncomplete()
    }

    fun suspendNextSettingsRead(): SuspendedTestOperation = settings.suspendNextRead()

    fun close() {
        processScope.cancel()
    }

    override fun captureDependencies(): CaptureDependencies = CaptureDependencies(
        settings = settings,
        repository = repository,
        speechFactory = {
            speechFactoryCalls.incrementAndGet()
            speechEngine
        },
        completionSignals = completionSignals.signals,
        clock = Clock.fixed(
            Instant.parse("2026-08-30T17:00:00Z"),
            ZoneId.of("America/New_York"),
        ),
        zone = ZoneId.of("America/New_York"),
        ioDispatcher = Dispatchers.IO,
        recoveryHandoff = recoveryHandoff,
        notificationRefresher = BackgroundNotificationRefresher(notificationRefresher, processScope),
    )
}

private class FakeCompletionSignals {
    private val mutableSignals = MutableSharedFlow<CompletionSignal>(extraBufferCapacity = 4)
    val activeCollectors = AtomicInteger()
    val signals: Flow<CompletionSignal> = mutableSignals
        .onStart { activeCollectors.incrementAndGet() }
        .onCompletion { activeCollectors.decrementAndGet() }

    fun simulate(action: String): Boolean =
        action == Intent.ACTION_SCREEN_OFF && mutableSignals.tryEmit(CompletionSignal.ScreenOff)
}

private class FakeSettingsRepository : SettingsRepository {
    private val mutableSettings = MutableStateFlow(
        AppSettings(
            treeUri = Uri.parse("content://com.sidenote.app.test.documents/root"),
            voiceOnAtLaunch = false,
            onboardingComplete = true,
        ),
    )
    val reads = AtomicInteger()
    @Volatile private var suspendedRead: SuspendedTestOperation? = null
    override val settings: Flow<AppSettings> = mutableSettings.onStart {
        reads.incrementAndGet()
        suspendedRead?.let { suspension ->
            suspension.started.complete(Unit)
            try {
                suspension.release.await()
                suspension.completed.complete(Unit)
            } catch (error: CancellationException) {
                suspension.cancelled.complete(Unit)
                throw error
            } finally {
                suspendedRead = null
            }
        }
    }

    fun suspendNextRead(): SuspendedTestOperation = SuspendedTestOperation().also {
        suspendedRead = it
    }

    fun markFolderUnavailable() {
        mutableSettings.value = mutableSettings.value.copy(
            treeUri = null,
            onboardingComplete = true,
        )
    }

    fun markOnboardingIncomplete() {
        mutableSettings.value = mutableSettings.value.copy(onboardingComplete = false)
    }

    override suspend fun setTreeUri(treeUri: Uri?) = Unit

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) = Unit

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) = Unit

    override suspend fun setOnboardingComplete(complete: Boolean) = Unit
}

private class FakeRecoveryDraftStore(
    private val events: MutableList<String>,
) : RecoveryDraftStore {
    val mainThreadCalls = AtomicInteger()
    val loads = AtomicInteger()
    private var hasDraft = true
    var draft = RecoveryDraft(
        text = "captured thought",
        selection = TextRange(16),
        voiceEnabled = false,
    )
    @Volatile private var suspendedSave: SuspendedTestOperation? = null
    @Volatile private var suspendedLoad: SuspendedTestOperation? = null

    fun suspendNextLoad(): SuspendedTestOperation = SuspendedTestOperation().also {
        suspendedLoad = it
    }

    fun suspendNextSave(): SuspendedTestOperation = SuspendedTestOperation().also {
        suspendedSave = it
    }

    override suspend fun load(): RecoveryLoadResult {
        recordCallingThread()
        loads.incrementAndGet()
        events += "load"
        suspendedLoad?.let { suspension ->
            suspension.started.complete(Unit)
            try {
                suspension.release.await()
                suspension.completed.complete(Unit)
            } catch (error: CancellationException) {
                suspension.cancelled.complete(Unit)
                throw error
            } finally {
                suspendedLoad = null
            }
        }
        return if (hasDraft) RecoveryLoadResult.Draft(draft) else RecoveryLoadResult.Empty
    }

    override suspend fun save(draft: RecoveryDraft) {
        recordCallingThread()
        suspendedSave?.let { suspension ->
            suspension.started.complete(Unit)
            try {
                suspension.release.await()
                suspension.completed.complete(Unit)
            } catch (error: CancellationException) {
                suspension.cancelled.complete(Unit)
                throw error
            } finally {
                suspendedSave = null
            }
        }
        events += "save"
        this.draft = draft
        hasDraft = true
    }

    override suspend fun clear() {
        hasDraft = false
        recordCallingThread()
        events += "clear"
    }

    private fun recordCallingThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainThreadCalls.incrementAndGet()
        }
    }
}

private class RecordingCaptureRepository(
    private val events: MutableList<String>,
) : DocumentRepository {
    val appends = CopyOnWriteArrayList<String>()
    val mainThreadCalls = AtomicInteger()
    @Volatile private var suspendedAppend: SuspendedTestOperation? = null

    fun suspendNextAppend(): SuspendedTestOperation = SuspendedTestOperation().also {
        suspendedAppend = it
    }

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainThreadCalls.incrementAndGet()
        }
        events += "append"
        appends += text
        suspendedAppend?.let { suspension ->
            suspension.started.complete(Unit)
            try {
                suspension.release.await()
                suspension.completed.complete(Unit)
            } catch (error: CancellationException) {
                suspension.cancelled.complete(Unit)
                throw error
            } finally {
                suspendedAppend = null
            }
        }
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
    val startCalls = AtomicInteger()

    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available

    override suspend fun requestModelDownloads() = Unit

    override fun start(listener: SpeechEngine.Listener) {
        startCalls.incrementAndGet()
    }

    override fun stop() = Unit

    override fun destroy() = Unit
}

private class SuspendedTestOperation {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val completed = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
}
