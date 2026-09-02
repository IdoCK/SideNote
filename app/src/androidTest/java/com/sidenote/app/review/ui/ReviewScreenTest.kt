package com.sidenote.app.review.ui

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.MainActivity
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownEntry
import com.sidenote.app.data.markdown.ProjectToken
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.review.ProjectGroup
import com.sidenote.app.review.ReviewDay
import com.sidenote.app.review.ReviewEntry
import com.sidenote.app.review.ReviewEntryId
import com.sidenote.app.review.ReviewState
import com.sidenote.app.review.ReviewTab
import java.time.LocalDate
import java.time.LocalTime
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun datesExposeExplicitNavigationAccessibleRowsAndKeepProcessedSourceOrder() {
        val older = entry(
            date = "2026-08-27",
            time = "08:15",
            text = "Plan שיפוץ @Home",
            processed = false,
            ordinal = 0,
        )
        val processed = entry(
            date = "2026-08-27",
            time = "09:45",
            text = "Already handled",
            processed = true,
            ordinal = 1,
        )
        var state by mutableStateOf(
            ReviewState(
                days = listOf(
                    ReviewDay(LocalDate.parse("2026-08-26"), emptyList()),
                    ReviewDay(LocalDate.parse("2026-08-27"), listOf(older, processed)),
                    ReviewDay(LocalDate.parse("2026-08-28"), emptyList()),
                ),
                selectedDate = LocalDate.parse("2026-08-27"),
            ),
        )
        var previousCalls = 0
        var nextCalls = 0
        var settingsCalls = 0

        setContent {
            ReviewScreen(
                state = state,
                onShowDates = {},
                onShowProjects = {},
                onPreviousDay = { previousCalls += 1 },
                onNextDay = { nextCalls += 1 },
                onOpenSettings = { settingsCalls += 1 },
                onToggleExpanded = { target ->
                    val id = target.id
                    state = state.copy(
                        expanded = state.expanded.toMutableSet().apply {
                            if (!add(id)) remove(id)
                        },
                    )
                },
                onProcessedChange = { _, _ -> },
                onOpenProject = {},
                onOpenSourceDay = {},
            )
        }

        compose.onNodeWithText("Dates").assertIsDisplayed()
        compose.onNodeWithText("Projects").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.onNodeWithContentDescription("Next day").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.runOnIdle {
            assertThat(previousCalls).isEqualTo(1)
            assertThat(nextCalls).isEqualTo(1)
            assertThat(settingsCalls).isEqualTo(1)
        }

        val checkboxes = compose.onAllNodesWithTag(REVIEW_CHECKBOX_TAG).fetchSemanticsNodes()
        assertThat(checkboxes).hasSize(2)
        checkboxes.forEach { checkbox ->
            assertThat(checkbox.boundsInRoot.width).isAtLeast(48f)
            assertThat(checkbox.boundsInRoot.height).isAtLeast(48f)
        }
        compose.onNodeWithContentDescription("Mark note 1 processed").assertIsOff()
        compose.onNodeWithContentDescription("Mark note 2 unprocessed").assertIsOn()

        val timestamp = compose.onNodeWithTag("$REVIEW_TIMESTAMP_TAG.0")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .text
        assertThat(timestamp).isEqualTo("\u206608:15\u2069")

        val olderBounds = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").getUnclippedBoundsInRoot()
        val processedBounds = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.1").getUnclippedBoundsInRoot()
        assertThat(olderBounds.top.value).isLessThan(processedBounds.top.value)
        val processedText = compose.onNodeWithText("Already handled")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
        assertThat(
            processedText.spanStyles.any { range ->
                range.item.textDecoration == TextDecoration.LineThrough
            },
        ).isTrue()

        compose.onNodeWithContentDescription("Expand note 1")
            .assertStateDescription("Collapsed")
            .performClick()
        compose.onNodeWithContentDescription("Collapse note 1")
            .assertStateDescription("Expanded")
        compose.onNodeWithText("Plan שיפוץ @Home", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun projectsShowCountsNewestFirstAndNavigateThroughOriginalSourceDay() {
        val older = entry("2026-08-26", "08:15", "@SideNote old", false, 0)
        val newer = entry("2026-08-27", "19:45", "@sidenote new", false, 0)
        var openedProject: String? = null
        var openedSource: ReviewEntry? = null
        var state by mutableStateOf(
            ReviewState(
                days = listOf(
                    ReviewDay(older.entry.date, listOf(older)),
                    ReviewDay(newer.entry.date, listOf(newer)),
                ),
                selectedDate = newer.entry.date,
                tab = ReviewTab.Projects,
                projects = listOf(
                    ProjectGroup("sidenote", "SideNote", listOf(newer, older)),
                ),
            ),
        )

        setContent {
            ReviewScreen(
                state = state,
                onShowDates = {},
                onShowProjects = {},
                onPreviousDay = {},
                onNextDay = {},
                onOpenSettings = {},
                onToggleExpanded = {},
                onProcessedChange = { _, _ -> },
                onOpenProject = { key ->
                    openedProject = key
                    state = state.copy(selectedProjectKey = key)
                },
                onOpenSourceDay = { openedSource = it },
            )
        }

        compose.onNodeWithText("SideNote").assertIsDisplayed().performClick()
        compose.runOnIdle { assertThat(openedProject).isEqualTo("sidenote") }
        compose.onNodeWithText("2 entries").assertIsDisplayed()
        val newerBounds = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").getUnclippedBoundsInRoot()
        val olderBounds = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.1").getUnclippedBoundsInRoot()
        assertThat(newerBounds.top.value).isLessThan(olderBounds.top.value)

        compose.onNodeWithText("Open 2026-08-26").performClick()
        compose.runOnIdle { assertThat(openedSource).isEqualTo(older) }
    }

    @Test
    fun settingsExposeExactControlsAndRequirePlainLanguagePrivacyDisclosure() {
        var folderCalls = 0
        var permissionCalls = 0
        var fallback: Boolean? = null
        val settings = AppSettings(
            treeUri = Uri.parse("content://notes/tree/SideNote"),
            voiceOnAtLaunch = true,
            onlineFallbackAllowed = false,
            voiceDisclosureAccepted = false,
            onboardingComplete = true,
        )

        setContent {
            SettingsScreen(
                settings = settings,
                folderLabel = "SideNote",
                microphoneGranted = false,
                notificationsGranted = true,
                onBack = {},
                onChooseFolder = { folderCalls += 1 },
                onVoiceOnAtLaunchChange = {},
                onOnlineFallbackChange = { fallback = it },
                onRequestPermissions = { permissionCalls += 1 },
            )
        }

        compose.onNodeWithText("Notes folder").assertIsDisplayed()
        compose.onNodeWithText("Change folder").performClick()
        compose.onNodeWithText("Voice on at launch").assertIsDisplayed()
        compose.onNodeWithText("Allow online voice recognition").performClick()
        compose.onNodeWithText("Microphone: Not allowed").assertIsDisplayed()
        compose.onNodeWithText("Notifications: Allowed").assertIsDisplayed()
        compose.onNodeWithText("Review permissions").performClick()
        compose.onNodeWithText(
            "Settings → System → Gestures → Quick Tap → Open app → SideNote",
        ).assertIsDisplayed()
        compose.onNodeWithText("Markdown files are the source of truth", substring = true)
            .assertIsDisplayed()
        compose.runOnIdle {
            assertThat(folderCalls).isEqualTo(1)
            assertThat(permissionCalls).isEqualTo(1)
            assertThat(fallback).isNull()
        }

        compose.onNodeWithText(
            "Voice is processed on this phone when available. When necessary, Android's speech service may process it online.",
        ).assertIsDisplayed()
        compose.onNodeWithText("SideNote has no backend", substring = true).assertIsDisplayed()
        compose.onNodeWithText("not end-to-end private", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow online voice").performClick()
        compose.runOnIdle { assertThat(fallback).isTrue() }
    }

    @Test
    fun mixedHebrewAtFontScaleTwoKeepsPrimaryControlsInsideTheReviewSurface() {
        val mixed = entry(
            "2026-08-27",
            "14:26",
            "Call דנה about @Home renovation",
            false,
            0,
        )
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ReviewScreen(
                    state = ReviewState(
                        days = listOf(ReviewDay(mixed.entry.date, listOf(mixed))),
                        selectedDate = mixed.entry.date,
                        expanded = setOf(mixed.id),
                    ),
                    onShowDates = {},
                    onShowProjects = {},
                    onPreviousDay = {},
                    onNextDay = {},
                    onOpenSettings = {},
                    onToggleExpanded = {},
                    onProcessedChange = { _, _ -> },
                    onOpenProject = {},
                    onOpenSourceDay = {},
                )
            }
        }

        val root = compose.onNodeWithTag(REVIEW_ROOT_TAG).bounds()
        listOf(
            compose.onNodeWithText("Dates").bounds(),
            compose.onNodeWithText("Projects").bounds(),
            compose.onNodeWithContentDescription("Settings").bounds(),
            compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").bounds(),
        ).forEach { child -> assertContained(child, root) }
        compose.onNodeWithText("Call דנה about @Home renovation", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun entry(
        date: String,
        time: String,
        text: String,
        processed: Boolean,
        ordinal: Int,
    ): ReviewEntry {
        val source = EntrySource(
            lineStart = ordinal + 2,
            rawTask = "- [${if (processed) "x" else " "}] **$time** $text",
            ordinal = ordinal,
        )
        return ReviewEntry(
            entry = MarkdownEntry(
                date = LocalDate.parse(date),
                time = LocalTime.parse(time),
                text = text,
                processed = processed,
                projects = listOf(ProjectToken("sidenote", "SideNote")),
                source = source,
            ),
            fileName = "$date.md",
            expectedRaw = "# $date\n\n${source.rawTask}\n",
        )
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertStateDescription(
        expected: String,
    ): androidx.compose.ui.test.SemanticsNodeInteraction = apply {
        val actual = fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertThat(actual).isEqualTo(expected)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.bounds(): Rect =
        fetchSemanticsNode().boundsInRoot

    private fun assertContained(child: Rect, parent: Rect) {
        assertThat(child.left).isAtLeast(parent.left)
        assertThat(child.top).isAtLeast(parent.top)
        assertThat(child.right).isAtMost(parent.right)
        assertThat(child.bottom).isAtMost(parent.bottom)
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { activity -> activity.setContent(content = content) }
        compose.waitForIdle()
    }
}
