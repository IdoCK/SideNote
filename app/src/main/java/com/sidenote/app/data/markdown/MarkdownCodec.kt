package com.sidenote.app.data.markdown

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MarkdownCodec {
    private val taskPattern = Regex("""^- \[([ xX])] \*\*(\d{2}:\d{2})\*\*(?: (.*))?$""")

    fun createDailyFile(date: LocalDate): String = "# $date\n"

    fun appendEntry(existing: String, capture: NewCapture): String {
        val normalizedCapture = capture.text.replace("\r\n", "\n").replace('\r', '\n')
        val captureLines = normalizedCapture.split('\n')
        val firstLine = captureLines.firstOrNull().orEmpty()
        val task = buildString {
            append("- [ ] **")
            append(capture.time.format(TIME_FORMAT))
            append("**")
            if (firstLine.isNotEmpty()) {
                append(' ')
                append(firstLine)
            }
            captureLines.drop(1).forEach { continuation ->
                append("\n  ")
                append(continuation)
            }
            append('\n')
        }
        val separator = when {
            existing.isEmpty() -> ""
            existing.endsWith("\n\n") || existing.endsWith("\r\n\r\n") -> ""
            existing.endsWith('\n') || existing.endsWith('\r') -> "\n"
            else -> "\n\n"
        }
        return existing + separator + task
    }

    fun parse(date: LocalDate, text: String): ParsedDailyFile {
        val lines = splitLines(text)
        val entries = mutableListOf<MarkdownEntry>()
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val match = taskPattern.matchEntire(line.content)
            val time = match?.groupValues?.get(2)?.let(::parseTimeOrNull)
            if (match == null || time == null) {
                lineIndex += 1
                continue
            }

            var lastTaskLine = lineIndex
            while (
                lastTaskLine + 1 < lines.size &&
                lines[lastTaskLine].separator.isNotEmpty() &&
                lines[lastTaskLine + 1].content.isIndented()
            ) {
                lastTaskLine += 1
            }
            val logicalText = buildList {
                add(match.groupValues[3])
                for (continuationIndex in (lineIndex + 1)..lastTaskLine) {
                    add(lines[continuationIndex].content.removeContinuationIndent())
                }
            }.joinToString("\n")
            val rawEnd = lines[lastTaskLine].start + lines[lastTaskLine].content.length
            val rawTask = text.substring(line.start, rawEnd)
            entries += MarkdownEntry(
                date = date,
                time = time,
                text = logicalText,
                processed = match.groupValues[1].equals("x", ignoreCase = true),
                projects = ProjectSyntax.tokens(logicalText),
                source = EntrySource(
                    lineStart = line.start,
                    rawTask = rawTask,
                    ordinal = entries.size,
                ),
            )
            lineIndex = lastTaskLine + 1
        }
        return ParsedDailyFile(date = date, entries = entries, raw = text)
    }

    fun rewriteProcessed(text: String, source: EntrySource, processed: Boolean): RewriteResult {
        val lines = splitLines(text)
        val sourceLineIndex = lines.indexOfFirst { it.start == source.lineStart }
        if (sourceLineIndex < 0) return RewriteResult.Conflict
        val firstLine = lines[sourceLineIndex]
        val firstMatch = taskPattern.matchEntire(firstLine.content) ?: return RewriteResult.Conflict
        if (parseTimeOrNull(firstMatch.groupValues[2]) == null) return RewriteResult.Conflict

        var lastTaskLine = sourceLineIndex
        while (
            lastTaskLine + 1 < lines.size &&
            lines[lastTaskLine].separator.isNotEmpty() &&
            lines[lastTaskLine + 1].content.isIndented()
        ) {
            lastTaskLine += 1
        }
        val rawEnd = lines[lastTaskLine].start + lines[lastTaskLine].content.length
        val currentRawTask = text.substring(firstLine.start, rawEnd)
        if (currentRawTask != source.rawTask) return RewriteResult.Conflict

        val currentOrdinal = lines.take(sourceLineIndex).count { line ->
            val match = taskPattern.matchEntire(line.content)
            match != null && parseTimeOrNull(match.groupValues[2]) != null
        }
        if (currentOrdinal != source.ordinal) return RewriteResult.Conflict

        val checkboxIndex = firstLine.start + CHECKBOX_MARK_OFFSET
        val replacement = if (processed) 'x' else ' '
        return RewriteResult.Updated(
            text.substring(0, checkboxIndex) + replacement + text.substring(checkboxIndex + 1),
        )
    }

    private fun splitLines(text: String): List<LineRecord> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<LineRecord>()
        var lineStart = 0
        var cursor = 0
        while (cursor < text.length) {
            when (text[cursor]) {
                '\r' -> {
                    val separatorLength = if (cursor + 1 < text.length && text[cursor + 1] == '\n') 2 else 1
                    lines += LineRecord(
                        start = lineStart,
                        content = text.substring(lineStart, cursor),
                        separator = text.substring(cursor, cursor + separatorLength),
                    )
                    cursor += separatorLength
                    lineStart = cursor
                }
                '\n' -> {
                    lines += LineRecord(
                        start = lineStart,
                        content = text.substring(lineStart, cursor),
                        separator = "\n",
                    )
                    cursor += 1
                    lineStart = cursor
                }
                else -> cursor += 1
            }
        }
        if (lineStart < text.length) {
            lines += LineRecord(start = lineStart, content = text.substring(lineStart), separator = "")
        }
        return lines
    }

    private fun parseTimeOrNull(value: String): LocalTime? {
        val hour = value.substring(0, 2).toIntOrNull() ?: return null
        val minute = value.substring(3, 5).toIntOrNull() ?: return null
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun String.isIndented(): Boolean = startsWith(' ') || startsWith('\t')

    private fun String.removeContinuationIndent(): String = when {
        startsWith("  ") -> substring(2)
        startsWith(' ') || startsWith('\t') -> substring(1)
        else -> this
    }

    private data class LineRecord(
        val start: Int,
        val content: String,
        val separator: String,
    )

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val CHECKBOX_MARK_OFFSET = 3
    }
}
