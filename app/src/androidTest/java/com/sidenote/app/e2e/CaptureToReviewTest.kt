package com.sidenote.app.e2e

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.MainActivity
import com.sidenote.app.MainDependencies
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.*
import com.sidenote.app.capture.ui.CAPTURE_INPUT_TAG
import com.sidenote.app.data.documents.*
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.recovery.AtomicFileRecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import com.sidenote.app.privacy.LockState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activities -> ViewModels -> repository -> SAF provider -> UTF-8 files. */
@RunWith(AndroidJUnit4::class)
class CaptureToReviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: DocumentAcceptanceFixture
    private var capture: ActivityScenario<CaptureActivity>? = null
    private var review: ActivityScenario<MainActivity>? = null
    private val announced = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        fixture = DocumentAcceptanceFixture()
        fixture.instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                announced.addAll(event.text.map { it.toString() })
            }
        }
    }
    @After fun tearDown() {
        capture?.close()
        review?.close()
        fixture.instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        fixture.close()
    }

    @Test fun successfulSaveAnnouncesItsCommitTimeExactlyOnce() {
        launchCapture()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("saved feedback")
        complete(CompletionSignal.ScreenOff)
        assertDocument("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** saved feedback\n")
        compose.waitUntil(5_000) { announced.contains("Saved · 18:26") }
        assertThat(announced.filter { it.startsWith("Saved ·") }).containsExactly("Saved · 18:26")
    }

    @Test fun deliberateDiscardAnnouncesWithoutWritingAnyDocument() {
        launchCapture()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("discard me")
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(5_000) { announced.contains("Discarded") }
        assertThat(fixture.documentNames()).isEmpty()
        assertThat(runBlocking { fixture.recovery.load() }).isEqualTo(RecoveryLoadResult.Empty)
    }

    @Test fun quickCaptureProducesOneUncheckedMarkdownEntryAndReviewProcessesIt() {
        launchCapture()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("רעיון חדש @SideNote")
        complete(CompletionSignal.ScreenOff)
        assertDocument("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** רעיון חדש @SideNote\n")

        openReview()
        compose.onNodeWithContentDescription("Mark note 1 processed").performClick()
        assertDocument("2026-08-27.md", "# 2026-08-27\n\n- [x] **18:26** רעיון חדש @SideNote\n")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Mark note 1 unprocessed").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription("Mark note 1 unprocessed").assertIsOn()
        assertThat(runBlocking { fixture.recovery.load() }).isEqualTo(RecoveryLoadResult.Empty)
    }

    @Test fun voicePartialFinalAndTypedEditsProduceOneLiteralMergedDocument() {
        launchCapture()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("Plan ")
        compose.onNodeWithContentDescription("Voice input off").performClick()
        compose.waitUntil(5_000) { fixture.speech.listener != null }
        compose.runOnIdle { fixture.speech.listener!!.onPartial("רעיון") }
        compose.onNodeWithText("Plan רעיון").assertIsDisplayed()
        compose.runOnIdle { fixture.speech.listener!!.onFinal("רעיון חדש") }
        compose.onNodeWithText("Plan רעיון חדש").assertIsDisplayed()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput(" @SideNote")
        complete(CompletionSignal.ScreenOff)
        assertDocument("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** Plan רעיון חדש @SideNote\n")
    }

    @Test fun simultaneousCompletionSignalsAndBackgroundingAppendExactlyOnce() {
        launchCapture()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("once @בית")
        runBlocking {
            coroutineScope {
                CompletionSignal.entries.forEach { signal -> launch { fixture.signals.emit(signal) } }
            }
        }
        assertDocument("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** once @בית\n")
        compose.waitForIdle()
        assertThat(fixture.documentNames()).containsExactly("2026-08-27.md")
        assertThat(fixture.readDocument("2026-08-27.md"))
            .isEqualTo("# 2026-08-27\n\n- [ ] **18:26** once @בית\n")
    }

    @Test fun failedProviderWritePreservesRecoveryAcrossRelaunchThenRetryWritesOnce() {
        fixture.seed("2026-08-27.md", "# 2026-08-27\n\nExternal paragraph.\n")
        fixture.control { TestDocumentsProvider.failWrites(it, true) }
        launchCapture()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("keep this שלום")
        complete(CompletionSignal.ScreenOff)
        compose.waitUntil(5_000) {
            (runBlocking { fixture.recovery.load() } as? RecoveryLoadResult.Draft)
                ?.draft?.text == "keep this שלום"
        }
        assertThat(fixture.readDocument("2026-08-27.md"))
            .isEqualTo("# 2026-08-27\n\nExternal paragraph.\n")
        capture?.close()
        capture = null
        fixture.control { TestDocumentsProvider.failWrites(it, false) }
        launchCapture()
        compose.onNodeWithText("keep this שלום").assertIsDisplayed()
        complete(CompletionSignal.ScreenOff)
        assertDocument(
            "2026-08-27.md",
            "# 2026-08-27\n\nExternal paragraph.\n\n- [ ] **18:26** keep this שלום\n",
        )
        compose.waitUntil(5_000) { runBlocking { fixture.recovery.load() } == RecoveryLoadResult.Empty }
    }

    @Test fun crossDayProjectsAreDerivedFromDocumentsAndUpdateOnlyTheirSourceCheckbox() {
        fixture.seed("2026-08-26.md", "# 2026-08-26\n\n- [ ] **09:05** Older @SideNote\n\nUnrelated **Markdown**.\n")
        fixture.seed("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** חדש @sidenote @בית\n")
        openReview()
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("SideNote").performClick()
        compose.onNodeWithText("2 entries").assertIsDisplayed()
        compose.onNodeWithText("חדש @sidenote @בית").assertIsDisplayed()
        compose.onNodeWithText("Older @SideNote").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark note 2 processed").performClick()
        assertDocument("2026-08-26.md", "# 2026-08-26\n\n- [x] **09:05** Older @SideNote\n\nUnrelated **Markdown**.\n")
        assertThat(fixture.readDocument("2026-08-27.md"))
            .isEqualTo("# 2026-08-27\n\n- [ ] **18:26** חדש @sidenote @בית\n")
        compose.onNodeWithText("Open 2026-08-26").performClick()
        compose.onNodeWithText("Older @SideNote").assertIsDisplayed()
    }

    private fun launchCapture() {
        capture = ActivityScenario.launch(CaptureActivity::class.java)
        compose.waitUntil(5_000) { fixture.signals.subscriptionCount.value > 0 }
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).assertIsDisplayed()
    }

    private fun openReview() {
        review = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Dates").assertIsDisplayed()
    }

    private fun complete(signal: CompletionSignal) = runBlocking { fixture.signals.emit(signal) }

    private fun assertDocument(name: String, expected: String) {
        compose.waitUntil(10_000) { fixture.readDocument(name) == expected }
        assertThat(fixture.readDocument(name)).isEqualTo(expected)
    }
}

/** Only external speech, lock state, and settings inputs are controllable; note/recovery I/O is real. */
internal class DocumentAcceptanceFixture : AutoCloseable, AppContainer {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val application = ApplicationProvider.getApplicationContext<SideNoteApplication>()
    private val original = application.container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryFile = File(context.cacheDir, "task-11-recovery-${System.nanoTime()}.json")
    val recovery = AtomicFileRecoveryDraftStore(recoveryFile)
    private val handoff = CaptureRecoveryHandoff(recovery, scope)
    val signals = MutableSharedFlow<CompletionSignal>(extraBufferCapacity = 8)
    val speech = AcceptanceSpeech()
    val lock = AcceptanceLock()
    val settings = AcceptanceSettings()
    val store = SafTextDocumentStore(context, TestDocumentsProvider.treeUri())
    val repository = MarkdownDocumentRepository(store, MarkdownCodec(), Mutex())

    init {
        control {
            TestDocumentsProvider.reset(it)
            TestDocumentsProvider.grantTree(it, context.packageName)
        }
        check(SafTreePermission.persist(context.contentResolver, TestDocumentsProvider.treeUri(), SafTreePermission.REQUIRED_FLAGS) == TreePermissionOutcome.Success)
        application.installContainerForTesting(this)
    }

    fun control(action: (android.content.ContentResolver) -> Unit) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try { action(context.contentResolver) } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    // Deliberately bypass SafTextDocumentStore/MarkdownCodec for fixture setup and assertions.
    fun seed(name: String, text: String) {
        val root = DocumentFile.fromTreeUri(context, TestDocumentsProvider.treeUri())!!
        val file = root.findFile(name) ?: root.createFile("text/plain", name)!!
        context.contentResolver.openOutputStream(file.uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    fun documentNames(): List<String> = DocumentFile.fromTreeUri(context, TestDocumentsProvider.treeUri())!!
        .listFiles().mapNotNull { it.name }

    fun readDocument(name: String): String? {
        val file = DocumentFile.fromTreeUri(context, TestDocumentsProvider.treeUri())!!.findFile(name) ?: return null
        return context.contentResolver.openInputStream(file.uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun captureDependencies() = CaptureDependencies(
        settings, repository, { speech }, signals,
        Clock.fixed(Instant.parse("2026-08-27T22:26:00Z"), ZoneId.of("America/New_York")),
        ZoneId.of("America/New_York"), Dispatchers.IO, handoff,
    )
    override fun mainDependencies() = MainDependencies(settings, repository, { speech }, Dispatchers.IO)
    override fun lockState(): LockState = lock

    override fun close() {
        application.installContainerForTesting(original)
        scope.cancel()
        runBlocking { recovery.clear() }
        context.contentResolver.releasePersistableUriPermission(TestDocumentsProvider.treeUri(), SafTreePermission.REQUIRED_FLAGS)
        control { TestDocumentsProvider.clear(it) }
    }
}

internal class AcceptanceSettings : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings(
        treeUri = TestDocumentsProvider.treeUri(), voiceOnAtLaunch = false,
        onboardingComplete = true, voiceDisclosureAccepted = true,
    ))
    override suspend fun setTreeUri(treeUri: Uri?) { settings.value = settings.value.copy(treeUri = treeUri) }
    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) { settings.value = settings.value.copy(voiceOnAtLaunch = enabled) }
    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) {
        settings.value = settings.value.copy(voiceDisclosureAccepted = true, onlineFallbackAllowed = allowed)
    }
    override suspend fun setOnboardingComplete(complete: Boolean) { settings.value = settings.value.copy(onboardingComplete = complete) }
}

internal class AcceptanceSpeech : SpeechEngine {
    @Volatile var listener: SpeechEngine.Listener? = null
    override suspend fun support() = SpeechAvailability.Available
    override suspend fun requestModelDownloads() = Unit
    override fun start(listener: SpeechEngine.Listener) { this.listener = listener }
    override fun stop() { listener = null }
    override fun destroy() { listener = null }
}

internal class AcceptanceLock : LockState {
    override val locked = MutableStateFlow(false)
    var actualLocked = false
    var atDismissal: ((Activity) -> Unit)? = null
    override fun refresh() { locked.value = actualLocked }
    override fun requestDismissKeyguard(activity: Activity) { atDismissal?.invoke(activity) }
}
