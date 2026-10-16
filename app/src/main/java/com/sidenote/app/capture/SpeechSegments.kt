package com.sidenote.app.capture

/** A provider may revise the live phrase; completed phrases are immutable. */
internal class SpeechSegments {
    private var committed = ""
    private var pending = ""
    val text: String get() = join(committed, pending)

    fun update(value: String): String {
        pending = value
        return text
    }

    fun commit(value: String): String {
        committed = join(committed, value)
        pending = ""
        return text
    }

    fun finalText(value: String): String = when {
        committed.isEmpty() -> value
        value.isBlank() -> text
        value == committed || (value.startsWith(committed) &&
            value.getOrNull(committed.length)?.let { it.isWhitespace() || it in ".,!?;:" } == true) -> value
        else -> join(committed, value)
    }

    private fun join(before: String, after: String): String = when {
        before.isEmpty() -> after
        after.isEmpty() -> before
        before.last().isWhitespace() || after.first().isWhitespace() || after.first() in ".,!?;:" -> before + after
        else -> "$before $after"
    }
}
