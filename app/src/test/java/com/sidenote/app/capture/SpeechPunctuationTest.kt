package com.sidenote.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPunctuationTest {
    @Test fun explicitCommandsBecomePunctuation() {
        val commands = mapOf(
            "period" to ".", "question mark" to "?", "comma" to ",",
            "exclamation mark" to "!", "exclamation point" to "!",
            "colon" to ":", "semicolon" to ";",
        )
        commands.forEach { (command, punctuation) ->
            assertEquals("Hello${punctuation} world", SpeechPunctuation.format("Hello insert $command world"))
        }
    }

    @Test fun recognizesCaseInsensitiveWholePhrases() {
        assertEquals("Hello?", SpeechPunctuation.format("Hello INSERT QUESTION MARK"))
        assertEquals("insert periods and reinsert comma", SpeechPunctuation.format("insert periods and reinsert comma"))
        assertEquals("שלוםinsert commaשלום", SpeechPunctuation.format("שלוםinsert commaשלום"))
    }

    @Test fun ordinaryTextIsUnchanged() {
        for (text in listOf("A period of time", "Question mark is a symbol", "  @project שלום, עולם!  ", "", "a\nb")) {
            assertEquals(text, SpeechPunctuation.format(text))
        }
    }

    @Test fun newlineCommandsPreserveParagraphStructureAndUnicode() {
        assertEquals("שלום\nעולם\n\n@project", SpeechPunctuation.format("שלום new line עולם insert new paragraph @project"))
        assertEquals("one\ntwo\n\nthree", SpeechPunctuation.format("one insert new line two new paragraph three"))
    }

    @Test fun formatterPunctuationDoesNotDuplicateInsertedSymbols() {
        assertEquals("Hello. World?", SpeechPunctuation.format("Hello insert period. World insert question mark?"))
        assertEquals("Hello.", SpeechPunctuation.format("Hello. insert period"))
        assertEquals("Yes!", SpeechPunctuation.format("Yes insert exclamation mark!"))
    }

    @Test fun cumulativeHypothesesRemainIdempotent() {
        val source = "@work Hello insert comma שלום insert period new paragraph Next insert colon detail"
        val formatted = "@work Hello, שלום.\n\nNext: detail"
        assertEquals(formatted, SpeechPunctuation.format(source))
        assertEquals(formatted, SpeechPunctuation.format(formatted))
    }
}
