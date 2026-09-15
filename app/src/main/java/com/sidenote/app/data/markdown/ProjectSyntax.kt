package com.sidenote.app.data.markdown

import java.util.Locale

object ProjectSyntax {
    private const val PROJECT_NAME = "[\\p{L}\\p{M}\\p{N}_-]+"
    private val tokenPattern = Regex("(?<![\\p{L}\\p{M}\\p{N}_])@($PROJECT_NAME)")
    private val whitespace = Regex("[ \\t]+")

    fun tokens(text: String): List<ProjectToken> {
        val tokensByKey = linkedMapOf<String, ProjectToken>()
        tokenPattern.findAll(text).forEach { match ->
            val display = match.groupValues[1]
            val key = display.lowercase(Locale.ROOT)
            tokensByKey.putIfAbsent(key, ProjectToken(key = key, display = display))
        }
        return tokensByKey.values.toList()
    }

    fun extractVoiceCommand(text: String, locale: Locale): VoiceCommandResult {
        val commandPhrase = when (locale.language.lowercase(Locale.ROOT)) {
            "en" -> "tag\\s+project"
            "he" -> "תייג\\s+פרויקט"
            else -> return VoiceCommandResult(text = text, projects = emptyList())
        }
        val options = if (locale.language.equals("en", ignoreCase = true)) {
            setOf(RegexOption.IGNORE_CASE)
        } else {
            emptySet()
        }
        val commandPattern = Regex(
            "(?:^|(?<=\\s))$commandPhrase\\s+($PROJECT_NAME)(?=\\s|[.,!?;:]|$)",
            options,
        )
        val projectsByKey = linkedMapOf<String, String>()
        commandPattern.findAll(text).forEach { match ->
            val display = match.groupValues[1]
            projectsByKey.putIfAbsent(display.lowercase(Locale.ROOT), display)
        }
        if (projectsByKey.isEmpty()) {
            return VoiceCommandResult(text = text, projects = emptyList())
        }

        val remainingText = commandPattern.replace(text, " ").trim().replace(whitespace, " ")
            .replace(Regex(" +(?=[.,!?;:])"), "")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        return VoiceCommandResult(
            text = remainingText,
            projects = projectsByKey.values.toList(),
        )
    }
}
