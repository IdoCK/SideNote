package com.sidenote.app.review

import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.RepositoryError
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.markdown.RewriteResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun projectsAggregateAcrossDaysNewestFirstWithoutCopies() = runTest(mainDispatcher) {
        val repository = FakeDocumentRepository(
            listOf(
                day("2026-08-26", "- [ ] **08:15** @SideNote old"),
                day("2026-08-27", "- [ ] **19:45** @sidenote new"),
            ),
        )

        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()

        val project = viewModel.state.value.projects.single()
        assertThat(project.key).isEqualTo("sidenote")
        assertThat(project.displayName).isEqualTo("SideNote")
        assertThat(project.entries.map { it.entry.text })
            .containsExactly("@sidenote new", "@SideNote old")
            .inOrder()
        assertThat(project.entries.map { it.fileName })
            .containsExactly("2026-08-27.md", "2026-08-26.md")
            .inOrder()
        assertThat(project.entries.map { it.expectedRaw })
            .containsExactly(repository.days[1].raw, repository.days[0].raw)
            .inOrder()
        assertThat(project.entries.all { it.entry.source.rawTask.isNotBlank() }).isTrue()
    }

    @Test
    fun projectDisplayUsesFirstChronologicalSpellingWhileSameDayEntriesAreNewestFirst() =
        runTest(mainDispatcher) {
            val repository = FakeDocumentRepository(
                listOf(
                    day(
                        "2026-08-26",
                        "- [ ] **08:15** @פרויקט note\n- [ ] **21:40** @פְּרוֹיֶקְט unrelated",
                    ),
                    day(
                        "2026-08-27",
                        "- [ ] **08:30** @פרויקט morning\n- [ ] **19:45** @פרויקט evening",
                    ),
                ),
            )

            val viewModel = ReviewViewModel(repository, mainDispatcher)
            advanceUntilIdle()

            val project = viewModel.state.value.projects.first { it.key == "פרויקט" }
            assertThat(project.displayName).isEqualTo("פרויקט")
            assertThat(project.entries.map { it.entry.text })
                .containsExactly("@פרויקט evening", "@פרויקט morning", "@פרויקט note")
                .inOrder()
        }

    @Test
    fun initialDateIsMostRecentWithAnUnprocessedEntryThenFallsBackToNewestDay() =
        runTest(mainDispatcher) {
            val repository = FakeDocumentRepository(
                listOf(
                    day("2026-08-25", "- [x] **08:00** done"),
                    day("2026-08-26", "- [ ] **09:00** pending"),
                    day("2026-08-27", "- [x] **10:00** newer done"),
                ),
            )
            val viewModel = ReviewViewModel(repository, mainDispatcher)
            advanceUntilIdle()

            assertThat(viewModel.state.value.selectedDate).isEqualTo(LocalDate.parse("2026-08-26"))

            val allProcessedRepository = FakeDocumentRepository(repository.days.map { parsed ->
                parsed.copy(
                    entries = parsed.entries.map { entry -> entry.copy(processed = true) },
                )
            })
            val allProcessedViewModel = ReviewViewModel(allProcessedRepository, mainDispatcher)
            advanceUntilIdle()

            assertThat(allProcessedViewModel.state.value.selectedDate)
                .isEqualTo(LocalDate.parse("2026-08-27"))
        }

    @Test
    fun previousAndNextFollowChronologicalSourceDatesAndStopAtBounds() = runTest(mainDispatcher) {
        val repository = FakeDocumentRepository(
            listOf(
                day("2026-08-25", "- [ ] **08:00** first"),
                day("2026-08-27", "- [ ] **09:00** second"),
                day("2026-08-31", "- [ ] **10:00** third"),
            ),
        )
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()

        viewModel.selectDate(LocalDate.parse("2026-08-27"))
        assertThat(viewModel.state.value.canGoPrevious).isTrue()
        assertThat(viewModel.state.value.canGoNext).isTrue()

        viewModel.previousDay()
        assertThat(viewModel.state.value.selectedDate).isEqualTo(LocalDate.parse("2026-08-25"))
        assertThat(viewModel.state.value.canGoPrevious).isFalse()
        viewModel.previousDay()
        assertThat(viewModel.state.value.selectedDate).isEqualTo(LocalDate.parse("2026-08-25"))

        viewModel.nextDay()
        viewModel.nextDay()
        assertThat(viewModel.state.value.selectedDate).isEqualTo(LocalDate.parse("2026-08-31"))
        assertThat(viewModel.state.value.canGoNext).isFalse()
        viewModel.nextDay()
        assertThat(viewModel.state.value.selectedDate).isEqualTo(LocalDate.parse("2026-08-31"))
    }

    @Test
    fun processedUpdateUsesExactSourceFileAndRawThenKeepsRowOrder() = runTest(mainDispatcher) {
        val sourceDay = day(
            "2026-08-27",
            "- [ ] **08:00** first @Work\n- [ ] **09:00** second @Work",
        )
        val repository = FakeDocumentRepository(listOf(sourceDay))
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()
        val first = viewModel.state.value.days.single().entries.first()

        viewModel.setProcessed(first, true)
        advanceUntilIdle()

        assertThat(repository.updates).containsExactly(
            UpdateCall(
                source = sourceDay.entries.first().source,
                fileName = "2026-08-27.md",
                expectedRaw = sourceDay.raw,
                processed = true,
            ),
        )
        val refreshed = viewModel.state.value.days.single().entries
        assertThat(refreshed.map { it.entry.text }).containsExactly("first @Work", "second @Work").inOrder()
        assertThat(refreshed.map { it.entry.processed }).containsExactly(true, false).inOrder()
    }

    @Test
    fun checkboxConflictReloadsInsteadOfOverwriting() = runTest(mainDispatcher) {
        val sourceDay = day("2026-08-27", "- [ ] **08:00** pending")
        val repository = FakeDocumentRepository(listOf(sourceDay)).apply {
            nextUpdate = UpdateResult.Conflict
        }
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()
        val sourceEntry = viewModel.state.value.days.single().entries.single()

        viewModel.setProcessed(sourceEntry, true)
        advanceUntilIdle()

        assertThat(repository.daysCalls).isEqualTo(2)
        assertThat(repository.updates).containsExactly(
            UpdateCall(
                source = sourceDay.entries.single().source,
                fileName = "2026-08-27.md",
                expectedRaw = sourceDay.raw,
                processed = true,
            ),
        )
        assertThat(viewModel.state.value.message).isEqualTo(ReviewMessage.FileChanged)
    }

    @Test
    fun failedUpdateReportsEverydayRecoveryMessageWithoutChangingRows() = runTest(mainDispatcher) {
        val sourceDay = day("2026-08-27", "- [ ] **08:00** pending")
        val repository = FakeDocumentRepository(listOf(sourceDay)).apply {
            nextUpdate = UpdateResult.Failure(RepositoryError.PermissionLost)
        }
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()

        viewModel.setProcessed(viewModel.state.value.days.single().entries.single(), true)
        advanceUntilIdle()

        assertThat(repository.daysCalls).isEqualTo(1)
        assertThat(viewModel.state.value.days.single().entries.single().entry.processed).isFalse()
        assertThat(viewModel.state.value.message).isEqualTo(ReviewMessage.FolderAccessLost)
    }

    @Test
    fun projectResultNavigationReturnsToTheExactSourceDay() = runTest(mainDispatcher) {
        val repository = FakeDocumentRepository(
            listOf(
                day("2026-08-26", "- [ ] **08:15** @SideNote old"),
                day("2026-08-27", "- [ ] **19:45** @sidenote new"),
            ),
        )
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()
        viewModel.showProjects()
        viewModel.openProject("sidenote")
        val oldEntry = viewModel.state.value.projects.single().entries.last()

        viewModel.openSourceDay(oldEntry)

        assertThat(viewModel.state.value.tab).isEqualTo(ReviewTab.Dates)
        assertThat(viewModel.state.value.selectedDate).isEqualTo(LocalDate.parse("2026-08-26"))
        assertThat(viewModel.state.value.selectedProjectKey).isNull()
    }

    @Test
    fun projectsTabReturnsFromAnOpenedProjectToTheProjectList() = runTest(mainDispatcher) {
        val repository = FakeDocumentRepository(
            listOf(day("2026-08-27", "- [ ] **19:45** @SideNote new")),
        )
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()
        viewModel.openProject("sidenote")
        assertThat(viewModel.state.value.selectedProjectKey).isEqualTo("sidenote")

        viewModel.showProjects()

        assertThat(viewModel.state.value.tab).isEqualTo(ReviewTab.Projects)
        assertThat(viewModel.state.value.selectedProjectKey).isNull()
    }

    @Test
    fun expansionIsInMemoryAndUsesTheCompleteSourceIdentity() = runTest(mainDispatcher) {
        val repository = FakeDocumentRepository(
            listOf(
                day("2026-08-26", "- [ ] **08:15** same text"),
                day("2026-08-27", "- [ ] **08:15** same text"),
            ),
        )
        val viewModel = ReviewViewModel(repository, mainDispatcher)
        advanceUntilIdle()
        val older = viewModel.state.value.days.first().entries.single()
        val newer = viewModel.state.value.days.last().entries.single()

        viewModel.toggleExpanded(older)

        assertThat(viewModel.state.value.isExpanded(older)).isTrue()
        assertThat(viewModel.state.value.isExpanded(newer)).isFalse()
        viewModel.toggleExpanded(older)
        assertThat(viewModel.state.value.isExpanded(older)).isFalse()
    }

    private fun day(date: String, tasks: String): ParsedDailyFile {
        val parsedDate = LocalDate.parse(date)
        val raw = "# $date\n\n$tasks\n"
        return MarkdownCodec().parse(parsedDate, raw)
    }
}

private data class UpdateCall(
    val source: EntrySource,
    val fileName: String,
    val expectedRaw: String,
    val processed: Boolean,
)

private class FakeDocumentRepository(
    days: List<ParsedDailyFile>,
) : DocumentRepository {
    var days: List<ParsedDailyFile> = days
    var daysCalls: Int = 0
    var nextUpdate: UpdateResult = UpdateResult.Success
    val updates = mutableListOf<UpdateCall>()
    private val codec = MarkdownCodec()

    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = error("ReviewViewModel must not append")

    override suspend fun days(): List<ParsedDailyFile> {
        daysCalls += 1
        return days
    }

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult {
        updates += UpdateCall(source, fileName, expectedRaw, processed)
        val result = nextUpdate.also { nextUpdate = UpdateResult.Success }
        if (result == UpdateResult.Success) {
            days = days.map { day ->
                if ("${day.date}.md" != fileName || day.raw != expectedRaw) return@map day
                val rewritten = codec.rewriteProcessed(day.raw, source, processed)
                check(rewritten is RewriteResult.Updated)
                codec.parse(day.date, rewritten.text)
            }
        }
        return result
    }

    override suspend fun uncheckedCount(): Int =
        days.sumOf { day -> day.entries.count { entry -> !entry.processed } }
}
