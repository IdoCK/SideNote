package com.sidenote.app.review

import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownEntry
import java.time.LocalDate

enum class ReviewTab {
    Dates,
    Projects,
}

enum class ReviewMessage {
    FileChanged,
    FolderAccessLost,
    CouldNotUpdate,
    UpdateUncertain,
    CouldNotLoad,
}

data class ReviewEntryId(
    val fileName: String,
    val source: EntrySource,
)

data class ReviewEntry(
    val entry: MarkdownEntry,
    val fileName: String,
    val expectedRaw: String,
) {
    val id: ReviewEntryId = ReviewEntryId(fileName, entry.source)
}

typealias ProjectEntry = ReviewEntry

data class ReviewDay(
    val date: LocalDate,
    val entries: List<ReviewEntry>,
    val sourceText: String = "",
)

data class ProjectGroup(
    val key: String,
    val displayName: String,
    val entries: List<ProjectEntry>,
)

data class ReviewState(
    val days: List<ReviewDay> = emptyList(),
    val selectedDate: LocalDate? = null,
    val projects: List<ProjectGroup> = emptyList(),
    val expanded: Set<ReviewEntryId> = emptySet(),
    val tab: ReviewTab = ReviewTab.Dates,
    val selectedProjectKey: String? = null,
    val message: ReviewMessage? = null,
) {
    val selectedDay: ReviewDay?
        get() = days.firstOrNull { day -> day.date == selectedDate }

    val selectedProject: ProjectGroup?
        get() = projects.firstOrNull { project -> project.key == selectedProjectKey }

    val canGoPrevious: Boolean
        get() = selectedDate != null && days.indexOfFirst { it.date == selectedDate } > 0

    val canGoNext: Boolean
        get() {
            val index = days.indexOfFirst { it.date == selectedDate }
            return index >= 0 && index < days.lastIndex
        }

    fun isExpanded(entry: ReviewEntry): Boolean = entry.id in expanded
}
