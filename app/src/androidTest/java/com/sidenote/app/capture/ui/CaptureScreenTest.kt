package com.sidenote.app.capture.ui

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.CaptureActivity
import com.sidenote.app.capture.CaptureDependencies
import com.sidenote.app.capture.CaptureRecoveryHandoff
import com.sidenote.app.capture.CaptureState
import com.sidenote.app.capture.CompletionSignal
import com.sidenote.app.capture.SpeechAvailability
import com.sidenote.app.capture.SpeechEngine
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<SideNoteApplication>()
    private lateinit var originalContainer: AppContainer
    private lateinit var container: CaptureUiTestContainer
    private var scenario: ActivityScenario<CaptureActivity>? = null

    @Before
    fun installFakeContainer() {
        originalContainer = application.container
        container = CaptureUiTestContainer()
        application.installContainerForTesting(container)
    }

    @After
    fun restoreApplication() {
        scenario?.close()
        container.close()
        application.installContainerForTesting(originalContainer)
    }

    @Test
    fun captureHasOnlyTheApprovedCaptureControls() {
        setContent {
            CaptureScreen(CaptureState(), {}, {}, {})
        }

        listOf("Save", "Done", "Listening", "Language", "History", "Settings", "Review")
            .forEach { prohibited ->
                compose.onNodeWithText(prohibited, substring = true).assertDoesNotExist()
            }
        compose.onNodeWithContentDescription("Voice input on")
            .assertIsDisplayed()
            .assertIsOn()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                ),
            )
    }

    @Test
    fun typingDispatchesTextAndBlobToggleIsAccessible() {
        val edits = mutableListOf<TextFieldValue>()
        var toggles = 0
        setContent {
            CaptureScreen(
                state = CaptureState(voiceEnabled = false),
                onTextChanged = { edits += it },
                onVoiceToggle = { toggles += 1 },
                onDiscard = {},
            )
        }

        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performTextInput("שלום")
        assertThat(edits.single().text).isEqualTo("שלום")

        compose.onNodeWithContentDescription("Voice input off")
            .assertIsOff()
            .performClick()
        assertThat(toggles).isEqualTo(1)
    }

    @Test
    fun discardAppearsOnlyForANonEmptyDraft() {
        var state by mutableStateOf(CaptureState())
        setContent {
            CaptureScreen(state, {}, {}, {})
        }

        compose.onNodeWithText("Discard").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(
                draft = TextFieldValue("not empty", TextRange(9)),
            )
        }
        compose.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun cardHasExactFourDpCornersAndBlobHasAtLeastFortyEightDpTarget() {
        setContent {
            CaptureScreen(CaptureState(), {}, {}, {})
        }

        compose.onNodeWithTag(CAPTURE_CARD_TAG).assert(
            SemanticsMatcher.expectValue(CaptureCardCornerRadius, 4f),
        )
        val blobBounds = compose.onNodeWithContentDescription("Voice input on")
            .fetchSemanticsNode()
            .boundsInRoot
        val density = compose.density.density
        assertThat(blobBounds.width / density).isAtLeast(48f)
        assertThat(blobBounds.height / density).isAtLeast(48f)
    }

    @Test
    fun englishAndHebrewUseContentDirectionAtLogicalStart() {
        var state by mutableStateOf(
            CaptureState(draft = TextFieldValue("English", TextRange(7))),
        )
        setContent {
            CaptureScreen(state, {}, {}, {})
        }

        val english = textLayoutResult()
        assertThat(english.getLineLeft(0)).isWithin(1f).of(0f)
        assertThat(english.getLineRight(0)).isLessThan(english.size.width / 2f)

        compose.runOnIdle {
            state = CaptureState(draft = TextFieldValue("עברית", TextRange(5)))
        }
        val hebrew = textLayoutResult()
        assertThat(hebrew.getLineLeft(0)).isGreaterThan(hebrew.size.width / 2f)
        assertThat(hebrew.getLineRight(0)).isWithin(1f).of(hebrew.size.width.toFloat())
    }

    @Test
    fun mixedBidirectionalTextAndDraftDerivedProjectChipsRemainVisible() {
        var state by mutableStateOf(
            CaptureState(
                draft = TextFieldValue(
                    "Plan שיפוץ 42 @Home @בית",
                    TextRange(25),
                ),
            ),
        )
        setContent {
            CaptureScreen(state, {}, {}, {})
        }

        compose.onNodeWithText("Plan שיפוץ 42 @Home @בית").assertIsDisplayed()
        compose.onNodeWithTag("$PROJECT_CHIP_TAG_PREFIX.Home").assertIsDisplayed()
        compose.onNodeWithTag("$PROJECT_CHIP_TAG_PREFIX.בית").assertIsDisplayed()

        compose.runOnIdle {
            state = CaptureState(draft = TextFieldValue("plain", TextRange(5)))
        }
        compose.onNodeWithTag("$PROJECT_CHIP_TAG_PREFIX.Home").assertDoesNotExist()
        compose.onNodeWithTag("$PROJECT_CHIP_TAG_PREFIX.בית").assertDoesNotExist()
    }

    @Test
    fun fontScaleTwoKeepsCaptureContentWithinTheVisibleRoot() {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                CaptureScreen(
                    state = CaptureState(
                        draft = TextFieldValue(
                            "Long mixed draft שצריך להישאר visible at large text",
                        ),
                    ),
                    onTextChanged = {},
                    onVoiceToggle = {},
                    onDiscard = {},
                )
            }
        }

        val root = compose.onNodeWithTag(CAPTURE_ROOT_TAG).bounds()
        val blob = compose.onNodeWithContentDescription("Voice input on")
            .assertIsDisplayed()
            .bounds()
        val card = compose.onNodeWithTag(CAPTURE_CARD_TAG).assertIsDisplayed().bounds()
        val input = compose.onNodeWithTag(CAPTURE_INPUT_TAG).assertIsDisplayed().bounds()
        val discard = compose.onNodeWithText("Discard").assertIsDisplayed().bounds()
        val textLayout = textLayoutResult()
        assertContained(blob, root)
        assertContained(card, root)
        assertContained(input, card)
        assertContained(discard, root)
        assertThat(textLayout.getLineBottom(textLayout.lineCount - 1))
            .isAtMost(input.height)
    }

    private fun textLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(CAPTURE_INPUT_TAG).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { action ->
            assertThat(action(results)).isTrue()
        }
        return results.single()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.bounds(): Rect =
        fetchSemanticsNode().boundsInRoot

    private fun assertContained(child: Rect, parent: Rect) {
        assertThat(child.left).isAtLeast(parent.left)
        assertThat(child.top).isAtLeast(parent.top)
        assertThat(child.right).isAtMost(parent.right)
        assertThat(child.bottom).isAtMost(parent.bottom)
    }

    private fun setContent(content: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(CaptureActivity::class.java)
        scenario?.onActivity { activity -> activity.setContent(content = content) }
        compose.waitForIdle()
    }
}

private class CaptureUiTestContainer : AppContainer {
    private val recovery = EmptyUiRecoveryStore()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryHandoff = CaptureRecoveryHandoff(recovery, processScope)

    override fun captureDependencies(): CaptureDependencies = CaptureDependencies(
        settings = UiSettingsRepository,
        repository = UiDocumentRepository,
        speechFactory = { UiSpeechEngine },
        completionSignals = MutableSharedFlow<CompletionSignal>(),
        clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
        zone = ZoneId.of("UTC"),
        ioDispatcher = Dispatchers.IO,
        recoveryHandoff = recoveryHandoff,
    )

    fun close() {
        processScope.cancel()
    }
}

private object UiSettingsRepository : SettingsRepository {
    override val settings: Flow<AppSettings> = MutableStateFlow(
        AppSettings(
            treeUri = null,
            voiceOnAtLaunch = false,
            onboardingComplete = false,
        ),
    )

    override suspend fun setTreeUri(treeUri: Uri?) = Unit

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) = Unit

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) = Unit

    override suspend fun setOnboardingComplete(complete: Boolean) = Unit
}

private object UiDocumentRepository : DocumentRepository {
    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = AppendResult.Success

    override suspend fun days(): List<ParsedDailyFile> = emptyList()

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Not used by CaptureScreenTest")

    override suspend fun uncheckedCount(): Int = 0
}

private class EmptyUiRecoveryStore : RecoveryDraftStore {
    override suspend fun load(): RecoveryLoadResult = RecoveryLoadResult.Empty

    override suspend fun save(draft: RecoveryDraft) = Unit

    override suspend fun clear() = Unit
}

private object UiSpeechEngine : SpeechEngine {
    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available

    override suspend fun requestModelDownloads() = Unit

    override fun start(listener: SpeechEngine.Listener) = Unit

    override fun stop() = Unit

    override fun destroy() = Unit
}
