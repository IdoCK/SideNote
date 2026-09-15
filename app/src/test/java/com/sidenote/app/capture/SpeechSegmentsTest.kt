package com.sidenote.app.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeechSegmentsTest {
    @Test fun cumulativeFinalCanAddPunctuationOrALineBreakWithoutRepeatingItsPrefix() {
        for (terminal in listOf("Hello, world", "Hello. World", "Hello\nworld")) {
            val segments = SpeechSegments()
            segments.commit("Hello")
            assertThat(segments.finalText(terminal)).isEqualTo(terminal)
        }
    }

    @Test fun sharedWordPrefixDoesNotEraseAnEarlierSegment() {
        val segments = SpeechSegments()
        segments.commit("No")
        assertThat(segments.finalText("Nothing else")).isEqualTo("No Nothing else")
    }
}
