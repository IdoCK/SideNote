package com.sidenote.app.capture.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.sidenote.app.data.markdown.ProjectToken

internal object ProjectSuggestionEditor {
    private val activePrefix = Regex("(?<![\\p{L}\\p{M}\\p{N}_])@([\\p{L}\\p{M}\\p{N}_-]*)$")

    fun matches(value: TextFieldValue, projects: List<ProjectToken>): List<ProjectToken> {
        val range = activeRange(value) ?: return emptyList()
        val prefix = value.text.substring(range.start + 1, range.end)
        return projects.filter { project -> project.display.startsWith(prefix, ignoreCase = true) }
    }

    fun apply(value: TextFieldValue, project: ProjectToken): TextFieldValue {
        val range = activeRange(value) ?: return value
        val replacement = "@${project.display}"
        val updatedText = value.text.replaceRange(range.start, range.end, replacement)
        val delta = replacement.length - (range.end - range.start)
        val updatedComposition = value.composition?.shiftAfterReplacement(range, delta)
        return TextFieldValue(
            text = updatedText,
            selection = TextRange(range.start + replacement.length),
            composition = updatedComposition,
        )
    }

    private fun activeRange(value: TextFieldValue): TextRange? {
        if (!value.selection.collapsed) return null
        val cursor = value.selection.start.coerceIn(0, value.text.length)
        if (cursor < value.text.length && value.text[cursor].isProjectNameCharacter()) return null
        val match = activePrefix.find(value.text.substring(0, cursor)) ?: return null
        return TextRange(match.range.first, cursor)
    }

    private fun TextRange.shiftAfterReplacement(replaced: TextRange, delta: Int): TextRange? = when {
        end <= replaced.start -> this
        start >= replaced.end -> TextRange(start + delta, end + delta)
        else -> null
    }

    private fun Char.isProjectNameCharacter(): Boolean =
        isLetterOrDigit() || this == '_' || this == '-' || Character.getType(this) in MARK_CATEGORIES

    private val MARK_CATEGORIES = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )
}
