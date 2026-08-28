package com.sidenote.app.data.markdown

import java.time.LocalDate
import java.time.LocalTime

data class NewCapture(
    val time: LocalTime,
    val text: String,
)

data class ProjectToken(
    val key: String,
    val display: String,
)

data class VoiceCommandResult(
    val text: String,
    val projects: List<String>,
)

data class EntrySource(
    val lineStart: Int,
    val rawTask: String,
    val ordinal: Int,
)

data class MarkdownEntry(
    val date: LocalDate,
    val time: LocalTime,
    val text: String,
    val processed: Boolean,
    val projects: List<ProjectToken>,
    val source: EntrySource,
)

data class ParsedDailyFile(
    val date: LocalDate,
    val entries: List<MarkdownEntry>,
    val raw: String,
)

sealed interface RewriteResult {
    data class Updated(val text: String) : RewriteResult

    data object Conflict : RewriteResult
}
