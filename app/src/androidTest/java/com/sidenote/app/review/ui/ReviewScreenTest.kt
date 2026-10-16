package com.sidenote.app.review.ui

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
        val selectedDates = mutableListOf<LocalDate>()
        var settingsCalls = 0

        setContent {
            ReviewScreen(
                state = state,
                onShowDates = {},
                onShowProjects = {},
                onPreviousDay = {},
                onNextDay = {},
                onSelectDate = { selectedDates += it },
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

        compose.onNodeWithContentDescription("Dates").assertIsDisplayed()
        compose.onNodeWithContentDescription("Projects").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open date 2026-08-26").performClick()
        compose.onNodeWithContentDescription("Open date 2026-08-27").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.runOnIdle {
            assertThat(selectedDates).containsExactly(LocalDate.parse("2026-08-26"), LocalDate.parse("2026-08-27")).inOrder()
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

        compose.onNodeWithContentDescription("Expand note 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand note 2").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dates").assertIsSelected()
        compose.onNodeWithText("Browse dates").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous day").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next day").assertDoesNotExist()
    }

    @Test
    fun onlySelectedDateHasBackgroundBeforeAndAfterSelection() {
        val dates = listOf("2026-09-05", "2026-09-08", "2026-09-10").map(LocalDate::parse)
        val note = entry("2026-09-05", "09:41", "Try a folded paper shade for the desk light. @Studio", false, 0)
        var state by mutableStateOf(ReviewState(
            days = dates.map { ReviewDay(it, if (it == dates.first()) listOf(note) else emptyList()) },
            selectedDate = dates.first(),
        ))
        setContent {
            ReviewScreen(
                state = state, onShowDates = {}, onShowProjects = {},
                onPreviousDay = {}, onNextDay = {}, onOpenSettings = {},
                onToggleExpanded = {}, onProcessedChange = { _, _ -> },
                onOpenProject = {}, onOpenSourceDay = {},
                onSelectDate = { state = state.copy(selectedDate = it) },
            )
        }
        fun assertDateSurfaces() {
            dates.forEach { date ->
                val node = compose.onNodeWithContentDescription("Open date $date")
                val bitmap = node.captureToImage().asAndroidBitmap()
                val expected = if (date == state.selectedDate) 0xFFF4F1EA.toInt() else 0xFF111111.toInt()
                // Sample the perimeter and inner padding to catch both borders and fills.
                listOf(0 to 0, 1 to bitmap.height / 2, 8 to 8,
                    bitmap.width - 1 to bitmap.height - 1).forEach { (x, y) ->
                    assertThat(bitmap.getPixel(x, y)).isEqualTo(expected)
                }
                if (date == state.selectedDate) node.assertIsSelected()
            }
        }
        saveScreenshot("review-dates-selected-first.png")
        assertDateSurfaces()
        compose.onNodeWithContentDescription("Open date ${dates[1]}").performClick()
        compose.waitForIdle()
        saveScreenshot("review-dates-selected-second.png")
        assertDateSurfaces()
    }

    @Test
    fun multilineNoteWithShortFirstLineStillExposesExpansion() {
        val note = entry("2026-08-27", "08:15", "First line\nMore detail", false, 0)
        var state by mutableStateOf(
            ReviewState(days = listOf(ReviewDay(note.entry.date, listOf(note))), selectedDate = note.entry.date),
        )
        setContent {
            motionReview(state, onToggleExpanded = {
                state = state.copy(expanded = if (state.expanded.isEmpty()) setOf(note.id) else emptySet())
            })
        }
        compose.onNodeWithContentDescription("Expand note 1").performClick()
        compose.onNodeWithText("First line\nMore detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse note 1").performClick()
        compose.onNodeWithContentDescription("Expand note 1").assertStateDescription("Collapsed")
    }

    @Test
    fun expandingAndCollapsingNoteMovesItsBottomGraduallyAndKeepsItsTopAnchored() {
        val note = entry("2026-08-27", "08:15", "A long note with enough detail to wrap onto several lines. ".repeat(8), false, 0)
        var state by mutableStateOf(
            ReviewState(days = listOf(ReviewDay(note.entry.date, listOf(note))), selectedDate = note.entry.date),
        )
        setContent {
            motionReview(state, onToggleExpanded = {
                state = state.copy(expanded = if (state.expanded.isEmpty()) setOf(note.id) else emptySet())
            })
        }
        val row = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0")
        val collapsed = row.getUnclippedBoundsInRoot()
        saveScreenshot("review-note-collapsed.png")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Expand note 1").performClick()
        compose.mainClock.advanceTimeBy(80)
        val expanding = row.getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(300)
        val expanded = row.getUnclippedBoundsInRoot()
        saveScreenshot("review-note-expanded.png")
        assertThat((expanding.bottom - expanding.top).value).isGreaterThan((collapsed.bottom - collapsed.top).value)
        assertThat((expanding.bottom - expanding.top).value).isLessThan((expanded.bottom - expanded.top).value)
        assertThat(expanding.top).isEqualTo(collapsed.top)
        compose.onNodeWithContentDescription("Collapse note 1").performClick()
        compose.mainClock.advanceTimeBy(80)
        val collapsing = row.getUnclippedBoundsInRoot()
        assertThat((collapsing.bottom - collapsing.top).value).isLessThan((expanded.bottom - expanded.top).value)
        assertThat((collapsing.bottom - collapsing.top).value).isGreaterThan((collapsed.bottom - collapsed.top).value)
        assertThat(collapsing.top).isEqualTo(collapsed.top)
        compose.mainClock.advanceTimeBy(300)
        val settled = row.getUnclippedBoundsInRoot()
        assertThat(settled.bottom - settled.top).isEqualTo(collapsed.bottom - collapsed.top)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun daySlidesFollowNavigationDirectionAndOnlyIncomingNotesAreAccessible() {
        val older = entry("2026-08-26", "08:15", "Older day note", false, 0)
        val newer = entry("2026-08-27", "09:00", "Newer day note", false, 0)
        var state by mutableStateOf(
            ReviewState(
                days = listOf(ReviewDay(older.entry.date, listOf(older)), ReviewDay(newer.entry.date, listOf(newer))),
                selectedDate = older.entry.date,
            ),
        )
        setContent {
            motionReview(
                state,
                onPreviousDay = { state = state.copy(selectedDate = older.entry.date) },
                onNextDay = { state = state.copy(selectedDate = newer.entry.date) },
                onSelectDate = { state = state.copy(selectedDate = it) },
            )
        }
        val restingLeft = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").getUnclippedBoundsInRoot().left.value
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Open date 2026-08-27").performClick()
        compose.mainClock.advanceTimeBy(80)
        val arrivingFromRight = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").getUnclippedBoundsInRoot().left.value
        assertThat(arrivingFromRight).isGreaterThan(restingLeft)
        compose.onNodeWithText("Older day note").assertDoesNotExist()
        // The outgoing page remains visually intact while hidden from the merged accessibility tree.
        compose.onNodeWithText("Older day note", useUnmergedTree = true).assertExists()
        assertThat(compose.onAllNodesWithTag(REVIEW_CHECKBOX_TAG).fetchSemanticsNodes()).hasSize(1)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Older day note", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Open date 2026-08-26").performClick()
        compose.mainClock.advanceTimeBy(80)
        val arrivingFromLeft = compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").getUnclippedBoundsInRoot().left.value
        assertThat(arrivingFromLeft).isLessThan(restingLeft)
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun switchingTabsKeepsOutgoingSnapshotWhileHidingItsActions() {
        val note = entry("2026-08-27", "08:15", "Date snapshot", false, 0)
        var state by mutableStateOf(
            ReviewState(days = listOf(ReviewDay(note.entry.date, listOf(note))), selectedDate = note.entry.date),
        )
        setContent {
            motionReview(state, onShowProjects = { state = state.copy(tab = ReviewTab.Projects) })
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Projects").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Date snapshot").assertDoesNotExist()
        compose.onNodeWithText("Date snapshot", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Expand note 1").assertDoesNotExist()
        compose.onNodeWithText("No project tags yet.").assertExists()
        compose.onNodeWithContentDescription("Projects").assertIsSelected()
        val movingLeft = compose.onNodeWithText("No project tags yet.").getUnclippedBoundsInRoot().left.value
        compose.mainClock.advanceTimeBy(300)
        val settledLeft = compose.onNodeWithText("No project tags yet.").getUnclippedBoundsInRoot().left.value
        assertThat(movingLeft).isGreaterThan(settledLeft)
        compose.onNodeWithText("Date snapshot", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun openingProjectPreservesTheOutgoingListAndExposesOnlyTheDetail() {
        val note = entry("2026-08-27", "08:15", "Project detail note", false, 0)
        var state by mutableStateOf(
            ReviewState(
                tab = ReviewTab.Projects,
                projects = listOf(ProjectGroup("sidenote", "SideNote", listOf(note))),
            ),
        )
        setContent {
            motionReview(state, onOpenProject = { state = state.copy(selectedProjectKey = it) })
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("SideNote").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("SideNote").assertExists()
        assertThat(compose.onAllNodesWithTag(REVIEW_CHECKBOX_TAG).fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithText("All projects").assertExists()
        val movingLeft = compose.onNodeWithText("Project detail note").getUnclippedBoundsInRoot().left.value
        compose.mainClock.advanceTimeBy(300)
        val settledLeft = compose.onNodeWithText("Project detail note").getUnclippedBoundsInRoot().left.value
        assertThat(movingLeft).isGreaterThan(settledLeft)
        compose.mainClock.autoAdvance = true
    }

    @androidx.compose.runtime.Composable
    private fun motionReview(
        state: ReviewState,
        onToggleExpanded: (ReviewEntry) -> Unit = {},
        onPreviousDay: () -> Unit = {},
        onNextDay: () -> Unit = {},
        onShowProjects: () -> Unit = {},
        onOpenProject: (String) -> Unit = {},
        onSelectDate: (LocalDate) -> Unit = {},
    ) {
        ReviewScreen(
            state = state,
            onShowDates = {},
            onShowProjects = onShowProjects,
            onPreviousDay = onPreviousDay,
            onNextDay = onNextDay,
            onSelectDate = onSelectDate,
            onOpenSettings = {},
            onToggleExpanded = onToggleExpanded,
            onProcessedChange = { _, _ -> },
            onOpenProject = onOpenProject,
            onOpenSourceDay = {},
        )
    }

    @Test
    fun originalMarkdownIsNotShownInReview() {
        val date = LocalDate.parse("2026-08-28")
        val raw = "# 2026-08-28\n\nOrdinary prose that SideNote does not edit.\n- [ ] malformed task\n"

        setContent {
            ReviewScreen(
                state = ReviewState(
                    days = listOf(ReviewDay(date, emptyList(), sourceText = raw)),
                    selectedDate = date,
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

        compose.onNodeWithText("Original Markdown · read only").assertDoesNotExist()
        compose.onNodeWithText("Ordinary prose that SideNote does not edit.", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("- [ ] malformed task", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No SideNote entries for this day.").assertIsDisplayed()
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
    fun longDateBrowserAndHorizontalSwipeNavigateWithoutExtraButtons() {
        val days = (0 until 120).map { offset ->
            ReviewDay(LocalDate.of(2026, 1, 1).plusDays(offset.toLong()), emptyList())
        }
        var state by mutableStateOf(
            ReviewState(days = days, selectedDate = days[60].date),
        )
        setContent {
            ReviewScreen(
                state = state,
                onShowDates = {},
                onShowProjects = {},
                onPreviousDay = {
                    val index = state.days.indexOfFirst { it.date == state.selectedDate }
                    state = state.copy(selectedDate = state.days[index - 1].date)
                },
                onNextDay = {
                    val index = state.days.indexOfFirst { it.date == state.selectedDate }
                    state = state.copy(selectedDate = state.days[index + 1].date)
                },
                onSelectDate = { state = state.copy(selectedDate = it) },
                onOpenSettings = {},
                onToggleExpanded = {},
                onProcessedChange = { _, _ -> },
                onOpenProject = {},
                onOpenSourceDay = {},
            )
        }

        compose.onNodeWithContentDescription("Previous day").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next day").assertDoesNotExist()
        val beforeSwipe = state.selectedDate
        compose.onNodeWithTag(REVIEW_DATES_CONTENT_TAG).performTouchInput { swipeLeft() }
        compose.runOnIdle { assertThat(state.selectedDate).isEqualTo(beforeSwipe?.plusDays(1)) }

        val oldest = days.first().date
        compose.onNodeWithTag(REVIEW_DATE_BROWSER_TAG)
            .performScrollToNode(hasContentDescription("Open date $oldest"))
        compose.onNodeWithContentDescription("Open date $oldest").performClick()
        compose.runOnIdle { assertThat(state.selectedDate).isEqualTo(oldest) }
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
                notificationsGranted = false,
                message = "That setting could not be saved. Please try again.",
                onBack = {},
                onChooseFolder = { folderCalls += 1 },
                onVoiceOnAtLaunchChange = {},
                onOnlineFallbackChange = { fallback = it },
                onRequestPermissions = { permissionCalls += 1 },
            )
        }

        compose.onNodeWithText("Notes folder").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("That setting could not be saved. Please try again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change folder").performScrollTo().performClick()
        compose.onNodeWithText("Voice on at launch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Microphone: Not allowed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Notifications: Not allowed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "The unprocessed-note reminder is unavailable. Capture and Review still work.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review permissions").performScrollTo().performClick()
        compose.onNodeWithText(
            "Settings → System → Gestures → Quick Tap → Open app → SideNote → Capture",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Markdown files are the source of truth", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertThat(folderCalls).isEqualTo(1)
            assertThat(permissionCalls).isEqualTo(1)
            assertThat(fallback).isNull()
        }

        compose.onNodeWithText("Allow online voice recognition").performScrollTo().performClick()
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
        var effectiveFontScale: Float? = null
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                effectiveFontScale = LocalDensity.current.fontScale
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
            compose.onNodeWithContentDescription("Dates").bounds(),
            compose.onNodeWithContentDescription("Projects").bounds(),
            compose.onNodeWithContentDescription("Settings").bounds(),
            compose.onNodeWithTag("$REVIEW_ENTRY_TAG.0").bounds(),
        ).forEach { child -> assertContained(child, root) }
        compose.onNodeWithText("Call דנה about @Home renovation", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.runOnIdle { assertThat(effectiveFontScale).isEqualTo(2f) }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
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
