package com.sidenote.app.capture

/** Explicit spoken editing commands only; sentence inference belongs to the recognizer. */
internal object SpeechPunctuation {
    private val commands = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}_@#])" +
            "(?:insert[ \\t]+(?:period|question[ \\t]+mark|comma|exclamation[ \\t]+(?:mark|point)|colon|semicolon|new[ \\t]+(?:line|paragraph))" +
            "|new[ \\t]+(?:line|paragraph))" +
            "(?![\\p{L}\\p{M}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )

    fun format(text: String): String {
        val matches = commands.findAll(text).iterator()
        if (!matches.hasNext()) return text
        val output = StringBuilder(text.length)
        var cursor = 0
        while (matches.hasNext()) {
            val match = matches.next()
            output.append(text, cursor, match.range.first)
            while (output.isNotEmpty() && output.last().isHorizontalSpace()) {
                output.setLength(output.length - 1)
            }
            val command = match.value.lowercase(java.util.Locale.ROOT)
                .replace(Regex("[ \\t]+"), " ").removePrefix("insert ")
            val replacement = when (command) {
                "period" -> "."
                "question mark" -> "?"
                "comma" -> ","
                "exclamation mark", "exclamation point" -> "!"
                "colon" -> ":"
                "semicolon" -> ";"
                "new line" -> "\n"
                "new paragraph" -> "\n\n"
                else -> error("Unrecognized punctuation command")
            }
            cursor = match.range.last + 1
            if (replacement.startsWith('\n')) {
                output.append(replacement)
                while (cursor < text.length && text[cursor].isHorizontalSpace()) cursor++
            } else {
                if (output.isEmpty() || output.last() != replacement[0]) output.append(replacement)
                // Native formatting may already have added this punctuation after
                // the spoken command. Consume only that matching symbol.
                var next = cursor
                while (next < text.length && text[next].isHorizontalSpace()) next++
                if (next < text.length && text[next] == replacement[0]) cursor = next + 1
            }
        }
        output.append(text, cursor, text.length)
        return output.toString()
    }

    private fun Char.isHorizontalSpace(): Boolean = this == ' ' || this == '\t'
}
