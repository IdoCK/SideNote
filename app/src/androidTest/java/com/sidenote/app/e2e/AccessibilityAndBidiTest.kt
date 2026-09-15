package com.sidenote.app.e2e

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.MainActivity
import com.sidenote.app.capture.*
import com.sidenote.app.capture.ui.*
import com.sidenote.app.onboarding.*
import com.sidenote.app.review.*
import com.sidenote.app.review.ui.*
import com.sidenote.app.ui.theme.SideNoteTheme
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityAndBidiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: DocumentAcceptanceFixture
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() { fixture = DocumentAcceptanceFixture() }
    @After fun tearDown() {
        fixture.lock.actualLocked = false
        fixture.lock.refresh()
        try { scenario?.close() } finally { fixture.close() }
    }

    @Test fun recoverableReviewErrorIsMonochromeAndAnnouncedPolitely() {
        content { ReviewFixture(ReviewState(message = ReviewMessage.CouldNotUpdate)) }
        val error = compose.onNodeWithText("That checkbox could not be updated. Your Markdown file was left unchanged.")
        error.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertMonochrome(error)
    }

    @Test fun recoverableOnboardingErrorIsMonochromeAndAnnouncedPolitely() {
        content {
            val settings by fixture.settings.settings.collectAsState()
            OnboardingScreen(
                OnboardingStep.Folder, settings, null,
                false, false, SpeechPreparationState.Idle, "Folder unavailable. Try again.",
                {}, {}, {}, {}, {}, {}, {},
            )
        }
        val error = compose.onNodeWithText("Folder unavailable. Try again.")
        error.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertMonochrome(error)
    }

    @Test fun captureFailureStatesHaveVisiblePoliteFeedbackWithoutAddingAListeningLabel() {
        var state by mutableStateOf(CaptureState(TextFieldValue("unsaved text"), status = CaptureStatus.SaveFailed))
        content { CaptureScreen(state, {}, {}, {}) }
        compose.onNodeWithText("Could not save. Your draft is kept; restore folder access in Settings and try again.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { state = state.copy(recoveryWriteFailed = true) }
        compose.onNodeWithText(
            "Could not save to your notes folder, and temporary recovery is unavailable. " +
                "Keep this screen open while you restore folder access and retry.",
        ).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { state = state.copy(status = CaptureStatus.SaveUncertain) }
        compose.onNodeWithText(
            "Android could not confirm the folder update, and temporary recovery is unavailable. " +
                "Keep this screen open and inspect the Markdown file before retrying.",
        ).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { state = state.copy(status = CaptureStatus.SpeechUnavailable) }
        compose.onNodeWithText("Voice is unavailable. You can keep typing.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Listening").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test fun englishHebrewAndMixedParagraphsUseContentBasedDirection() {
        var draft by mutableStateOf(TextFieldValue("English 42 @SideNote"))
        content { CaptureScreen(CaptureState(draft), { draft = it }, {}, {}) }
        assertThat(layout(compose.onNodeWithTag(CAPTURE_INPUT_TAG)).getParagraphDirection(0))
            .isEqualTo(ResolvedTextDirection.Ltr)
        compose.runOnIdle { draft = TextFieldValue("עברית 42 @SideNote") }
        assertThat(layout(compose.onNodeWithTag(CAPTURE_INPUT_TAG)).getParagraphDirection(0))
            .isEqualTo(ResolvedTextDirection.Rtl)
        compose.runOnIdle { draft = TextFieldValue("Plan שלום 42 @בית") }
        val mixed = layout(compose.onNodeWithTag(CAPTURE_INPUT_TAG))
        assertThat(mixed.getBidiRunDirection(0)).isEqualTo(ResolvedTextDirection.Ltr)
        assertThat(mixed.getBidiRunDirection(5)).isEqualTo(ResolvedTextDirection.Rtl)
        compose.onNodeWithText("Plan שלום 42 @בית").assertIsDisplayed()
    }

    @Test fun fontScaleTwoWrapsAllChipsAndPreservesEditingSelectionAndComposition() {
        val coordinator = CaptureCoordinator(
            fixture.repository, fixture.recovery, Mutex(), Clock.systemUTC(), ZoneId.of("UTC"),
            SpeechControl {}, HapticConfirmation {}, CaptureCloser {},
        )
        coordinator.start(false, null)
        val text = "@AlphaProject @BetaProject @GammaProject @בית"
        coordinator.onUserEdit(TextFieldValue(text, TextRange(1, 6), TextRange(1, 13)))
        content {
            val density = LocalDensity.current
            val state by coordinator.state.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                CaptureScreen(state, coordinator::onUserEdit, coordinator::onVoiceToggle, {})
            }
        }
        val root = compose.onNodeWithTag(CAPTURE_ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val bounds = listOf("AlphaProject", "BetaProject", "GammaProject", "בית").map { project ->
            val chip = compose.onNodeWithTag("$PROJECT_CHIP_TAG_PREFIX.$project")
            chip.performScrollTo().assertIsDisplayed()
            val textResult = layout(compose.onNodeWithText("@$project", useUnmergedTree = true))
            // TextLayoutResult's intrinsic-width flag can reflect fractional-pixel rounding.
            // Check actual glyph boxes with a one-pixel rasterization tolerance instead.
            ("@$project").indices.forEach { index ->
                val glyph = textResult.getBoundingBox(index)
                com.google.common.truth.Truth.assertWithMessage("$project glyph $index: $glyph, size=${textResult.size}")
                    .that(glyph.right).isAtMost(textResult.size.width + 1f)
                assertThat(glyph.left).isAtLeast(-1f)
                assertThat(glyph.bottom).isAtMost(textResult.size.height + 1f)
            }
            chip.fetchSemanticsNode().boundsInRoot.also {
                assertThat(it.left).isAtLeast(root.left)
                assertThat(it.right).isAtMost(root.right)
            }
        }
        assertThat(bounds.map { it.top }.distinct().size).isGreaterThan(1)
        val editor = compose.onNodeWithTag(CAPTURE_INPUT_TAG).performScrollTo().assertIsDisplayed()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 6)))
        assertThat(coordinator.state.value.draft.composition).isEqualTo(TextRange(1, 13))
        editor.performTextInputSelection(TextRange(text.length))
        compose.waitUntil(timeoutMillis = 5_000) {
            coordinator.state.value.draft.selection == TextRange(text.length)
        }
        editor.performTextInput("!")
        compose.waitForIdle()
        assertThat(coordinator.state.value.draft.text).isEqualTo("@AlphaProject @BetaProject @GammaProject @בית!")
        assertThat(coordinator.state.value.draft.selection).isEqualTo(TextRange(text.length + 1))
        compose.onNodeWithText("Discard").performScrollTo().assertIsDisplayed()
    }

    @Test fun reducedMotionKeepsBlobPixelsStillWhileOnOffStateRemainsAccessible() {
        var state by mutableStateOf(CaptureState())
        content {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                CaptureScreen(state, {}, { state = state.copy(voiceEnabled = !state.voiceEnabled) }, {})
            }
        }
        val voice = compose.onNodeWithContentDescription("Voice input on").assertIsOn()
        val bounds = voice.getUnclippedBoundsInRoot()
        assertThat(bounds.right.value - bounds.left.value).isAtLeast(48f)
        assertThat(bounds.bottom.value - bounds.top.value).isAtLeast(48f)
        val quiet = voice.captureToImage().toPixelMap()
        compose.runOnIdle { state = state.copy(rms = 1f) }
        val speaking = voice.captureToImage().toPixelMap()
        var differences = 0
        for (y in 0 until quiet.height) for (x in 0 until quiet.width) {
            if (quiet[x, y] != speaking[x, y]) differences++
        }
        assertThat(differences).isEqualTo(0)
        voice.performClick()
        compose.onNodeWithContentDescription("Voice input off").assertIsOff()
    }

    @Test fun actualReviewAtFontScaleTwoHasIsolatedTimeExpandableBodyAndClickableDateNavigation() {
        fixture.seed("2026-08-26.md", "# 2026-08-26\n\n- [ ] **09:05** English first\n")
        fixture.seed("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** רעיון חדש @SideNote 42\n  English continuation\n")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val originalScale = fixture.context.resources.configuration.fontScale
        // Override this Activity's resources and recreate; the fixture does not change device preferences.
        scenario!!.onActivity { activity ->
            val config = android.content.res.Configuration(activity.resources.configuration)
            config.fontScale = 2f
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        }
        scenario!!.recreate()
        scenario!!.onActivity { activity ->
            assertThat(activity.resources.configuration.fontScale).isEqualTo(2f)
        }
        try {
            compose.onNodeWithTag("$REVIEW_TIMESTAMP_TAG.0")
                .assertTextEquals("\u206618:26\u2069")
                .assertContentDescriptionEquals("Time 18:26")
            compose.onNodeWithContentDescription("Expand note 1")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
                .performClick()
            compose.onNodeWithContentDescription("Collapse note 1")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            val body = compose.onNodeWithText("רעיון חדש @SideNote 42\nEnglish continuation")
            body.performScrollTo().assertIsDisplayed()
            assertThat(layout(body).hasVisualOverflow).isFalse()
            compose.onNodeWithContentDescription("Open date 2026-08-26").performClick()
            compose.onNodeWithText("English first").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open date 2026-08-27").performClick()
            compose.onNodeWithTag("$REVIEW_TIMESTAMP_TAG.0").assertTextEquals("\u206618:26\u2069")
        } finally {
            scenario!!.onActivity { activity ->
                val config = android.content.res.Configuration(activity.resources.configuration)
                config.fontScale = originalScale
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            }
        }
    }

    @Test fun reusedWindowContainsOnlyUnlockSemanticsAtTheActualDismissalRequest() {
        fixture.seed("2026-08-27.md", "# 2026-08-27\n\n- [ ] **18:26** private reused frame\n")
        scenario = ActivityScenario.launch(Intent(fixture.context, MainActivity::class.java))
        compose.onNodeWithText("private reused frame").assertIsDisplayed()
        var atDismissal = emptyList<String>()
        fixture.lock.atDismissal = { activity ->
            atDismissal = viewRoots(activity.window.decorView).flatMap { root ->
                semanticText(root.semanticsOwner.rootSemanticsNode)
            }
        }
        fixture.lock.actualLocked = true // Deliberately do not publish before the reused intent.
        fixture.context.startActivity(Intent(fixture.context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        compose.onNodeWithText("Unlock SideNote").assertIsDisplayed()
        compose.runOnIdle {
            assertThat(atDismissal).contains("Unlock SideNote")
            assertThat(atDismissal).doesNotContain("private reused frame")
            assertThat(atDismissal).doesNotContain("Projects")
            assertThat(atDismissal).doesNotContain("Settings")
        }
    }

    private fun viewRoots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(viewRoots(view.getChildAt(index)))
    }

    private fun semanticText(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.children.flatMap(::semanticText)

    private fun content(body: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.setContent { SideNoteTheme(content = body) } }
        compose.waitForIdle()
    }

    @Composable private fun ReviewFixture(state: ReviewState) = ReviewScreen(state, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {})

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
        return results.single()
    }

    private fun assertMonochrome(node: SemanticsNodeInteraction) {
        val pixels = node.assertIsDisplayed().captureToImage().toPixelMap()
        var coloredPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > 0.08f) coloredPixels++
        }
        assertThat(coloredPixels).isEqualTo(0)
    }
}
