package com.sidenote.app

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** Guard the no-backend/no-note-database contract, not test fixtures or prose. */
class ArchitectureBoundaryTest {
    @Test fun productionHasNoDatabaseHttpAnalyticsOrRetainedAudioSubsystem() {
        val sourceRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val banned = listOf(
            "Room" to Regex("androidx\\.room|\\bRoomDatabase\\b"),
            "HTTP client" to Regex("okhttp3|retrofit2|io\\.ktor\\.client|HttpURLConnection|java\\.net\\.http|com\\.android\\.volley"),
            "analytics SDK" to Regex("firebase\\.analytics|FirebaseAnalytics|com\\.segment\\.analytics|com\\.amplitude|com\\.mixpanel"),
            "note/project/status database" to Regex("\\b(?:Note|Notes|Project|Projects|Status|Entry|Entries)(?:Database|Dao|Entity)\\b|SQLiteOpenHelper|SQLiteDatabase"),
            "raw audio retention" to Regex("\\b(?:MediaRecorder|AudioRecord)\\b"),
        )
        val violations = sourceRoot.walkTopDown().filter { it.extension in setOf("kt", "java", "xml") }
            .flatMap { file ->
                val source = file.readText()
                banned.filter { (_, pattern) -> pattern.containsMatchIn(source) }
                    .map { (boundary, _) -> "${file.relativeTo(sourceRoot)}: $boundary" }
            }.toList()
        assertThat(violations).isEmpty()
    }
}
