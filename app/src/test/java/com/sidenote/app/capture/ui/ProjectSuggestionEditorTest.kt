package com.sidenote.app.capture.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.markdown.ProjectToken
import org.junit.Test

class ProjectSuggestionEditorTest {
    private val projects = listOf(
        ProjectToken("home", "Home"),
        ProjectToken("home-renovation", "Home-Renovation"),
        ProjectToken("work", "Work"),
        ProjectToken("בית", "בית"),
    )

    @Test
    fun suggestionsExistOnlyForTheActiveAtPrefixAtTheCursor() {
        assertThat(ProjectSuggestionEditor.matches(TextFieldValue("Meet @ho", TextRange(8)), projects))
            .containsExactly(ProjectToken("home", "Home"), ProjectToken("home-renovation", "Home-Renovation"))
            .inOrder()
        assertThat(ProjectSuggestionEditor.matches(TextFieldValue("Meet @", TextRange(6)), projects))
            .containsExactlyElementsIn(projects)
            .inOrder()
        assertThat(ProjectSuggestionEditor.matches(TextFieldValue("Meet @ho later", TextRange(4)), projects))
            .isEmpty()
        assertThat(ProjectSuggestionEditor.matches(TextFieldValue("email@ho", TextRange(8)), projects))
            .isEmpty()
    }

    @Test
    fun applyingSuggestionReplacesOnlyPrefixAndKeepsUnaffectedSelectionAndCompositionOffsets() {
        val before = TextFieldValue(
            text = "שלום @ho then draft",
            selection = TextRange(8),
            composition = TextRange(14, 19),
        )

        val after = ProjectSuggestionEditor.apply(before, ProjectToken("home", "Home"))

        assertThat(after.text).isEqualTo("שלום @Home then draft")
        assertThat(after.selection).isEqualTo(TextRange(10))
        assertThat(after.composition).isEqualTo(TextRange(16, 21))
    }
}
