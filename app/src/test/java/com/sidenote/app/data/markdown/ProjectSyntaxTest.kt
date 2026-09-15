package com.sidenote.app.data.markdown

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class ProjectSyntaxTest {
    @Test
    fun formattedProjectCommandKeepsSentencePunctuationAndParagraphs() {
        val result = ProjectSyntax.extractVoiceCommand("Buy paint tag project Home.\n\nThen call Sam.", Locale.ENGLISH)
        assertThat(result.projects).containsExactly("Home")
        assertThat(result.text).isEqualTo("Buy paint.\n\nThen call Sam.")
    }
    @Test
    fun extractsUnicodeTagsAndExplicitHebrewCommand() {
        assertThat(ProjectSyntax.tokens("@SideNote וגם @שיפוץ-הבית").map { it.display })
            .containsExactly("SideNote", "שיפוץ-הבית")
        assertThat(ProjectSyntax.extractVoiceCommand("תייג פרויקט שיפוץ-הבית לקנות צבע", Locale.forLanguageTag("he")))
            .isEqualTo(VoiceCommandResult("לקנות צבע", listOf("שיפוץ-הבית")))
    }

    @Test
    fun preservesFirstSpellingWhileCaseFoldingUnicodeKeys() {
        val tokens = ProjectSyntax.tokens("@SideNote @sidenote @שיפוץ-הבית @שיפוץ-הבית")

        assertThat(tokens.map { it.display }).containsExactly("SideNote", "שיפוץ-הבית")
        assertThat(tokens.map { it.key }).containsExactly("sidenote", "שיפוץ-הבית")
    }

    @Test
    fun extractsRepeatedEnglishCommandsWithoutLeavingCommandWordsInProse() {
        val result = ProjectSyntax.extractVoiceCommand(
            "buy paint tag project Home-Renovation today tag project Errands",
            Locale.ENGLISH,
        )

        assertThat(result).isEqualTo(
            VoiceCommandResult("buy paint today", listOf("Home-Renovation", "Errands")),
        )
    }

    @Test
    fun extractsRepeatedHebrewCommandsAndKeepsOtherMixedText() {
        val result = ProjectSyntax.extractVoiceCommand(
            "לקנות צבע תייג פרויקט שיפוץ-הבית today תייג פרויקט SideNote",
            Locale.forLanguageTag("he"),
        )

        assertThat(result).isEqualTo(
            VoiceCommandResult("לקנות צבע today", listOf("שיפוץ-הבית", "SideNote")),
        )
    }

    @Test
    fun ordinaryAtWordIsNotACommand() {
        assertThat(ProjectSyntax.extractVoiceCommand("meet at noon", Locale.ENGLISH).projects).isEmpty()
    }

    @Test
    fun leavesOtherLocaleCommandTextUntouched() {
        val text = "tag project SideNote buy paint"

        assertThat(ProjectSyntax.extractVoiceCommand(text, Locale.forLanguageTag("he")))
            .isEqualTo(VoiceCommandResult(text, emptyList()))
    }
}
