package com.sidenote.app.data.documents

import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.markdown.MarkdownCodec
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MarkdownDocumentRepositoryTest {
    private val store = InMemoryTextDocumentStore()
    private val repo = MarkdownDocumentRepository(store, MarkdownCodec(), Mutex())

    @Test
    fun concurrentAppendsCreateOneDailyFileAndPreserveOrder() = runTest {
        coroutineScope {
            launch {
                repo.append(
                    "first",
                    Instant.parse("2026-08-27T13:00:00Z"),
                    zone("America/New_York"),
                )
            }
            launch {
                repo.append(
                    "second",
                    Instant.parse("2026-08-27T13:01:00Z"),
                    zone("America/New_York"),
                )
            }
        }

        assertThat(store.names()).containsExactly("2026-08-27.md")
        assertThat(repo.days().single().entries.map { it.text })
            .containsExactly("first", "second")
            .inOrder()
    }

    @Test
    fun writeFailureLeavesPriorTextUntouched() = runTest {
        val original = "# 2026-08-27\n\n- [ ] **08:00** Existing\n"
        store.put("2026-08-27.md", original)
        store.nextWriteOutcome = WriteOutcome.Failure(RepositoryError.WriteFailed)

        val result = repo.append(
            "new",
            Instant.parse("2026-08-27T13:00:00Z"),
            zone("America/New_York"),
        )

        assertThat(result).isEqualTo(AppendResult.Failure(RepositoryError.WriteFailed))
        assertThat(store.text("2026-08-27.md")).isEqualTo(original)
    }

    @Test
    fun permissionLossReturnsPermissionLost() = runTest {
        store.nextWriteOutcome = WriteOutcome.Failure(RepositoryError.PermissionLost)

        val result = repo.append(
            "not saved",
            Instant.parse("2026-08-27T13:00:00Z"),
            zone("America/New_York"),
        )

        assertThat(result).isEqualTo(AppendResult.Failure(RepositoryError.PermissionLost))
        assertThat(store.names()).isEmpty()
    }

    @Test
    fun malformedDailyFilesStayReadable() = runTest {
        val malformed = "# handwritten material\n\n- [ ] **not-a-time** keep me\n"
        store.put("2026-08-26.md", malformed)

        val day = repo.days().single()

        assertThat(day.raw).isEqualTo(malformed)
        assertThat(day.entries).isEmpty()
    }

    @Test
    fun targetedCheckboxConflictPerformsNoWrite() = runTest {
        val expected = "# 2026-08-27\n\n- [ ] **09:00** Original\n"
        val changed = "# 2026-08-27\n\n- [ ] **09:00** Changed elsewhere\n"
        val source = MarkdownCodec()
            .parse(java.time.LocalDate.parse("2026-08-27"), expected)
            .entries
            .single()
            .source
        store.put("2026-08-27.md", changed)

        val result = repo.setProcessed(source, "2026-08-27.md", expected, true)

        assertThat(result).isEqualTo(UpdateResult.Conflict)
        assertThat(store.writeAttempts).isEqualTo(0)
        assertThat(store.text("2026-08-27.md")).isEqualTo(changed)
    }

    @Test
    fun processedUpdateChangesOnlyTargetAndUncheckedCountComesFromMarkdown() = runTest {
        val original = buildString {
            append("# 2026-08-27\n\n")
            append("- [ ] **09:00** First\n")
            append("- [ ] **10:00** Second\n")
        }
        store.put("2026-08-27.md", original)
        val source = MarkdownCodec()
            .parse(java.time.LocalDate.parse("2026-08-27"), original)
            .entries
            .first()
            .source

        val result = repo.setProcessed(source, "2026-08-27.md", original, true)

        assertThat(result).isEqualTo(UpdateResult.Success)
        assertThat(store.text("2026-08-27.md")).isEqualTo(
            "# 2026-08-27\n\n- [x] **09:00** First\n- [ ] **10:00** Second\n",
        )
        assertThat(repo.uncheckedCount()).isEqualTo(1)
    }

    private fun zone(id: String): ZoneId = ZoneId.of(id)
}

private class InMemoryTextDocumentStore : TextDocumentStore {
    private val documents = linkedMapOf<String, String>()

    var nextWriteOutcome: WriteOutcome = WriteOutcome.Success
    var writeAttempts: Int = 0
        private set

    override suspend fun listNames(): List<String> = documents.keys.toList()

    override suspend fun read(name: String): String? = documents[name]

    override suspend fun writeAtomically(
        name: String,
        expected: String?,
        replacement: String,
    ): WriteOutcome {
        writeAttempts += 1
        val configuredOutcome = nextWriteOutcome
        nextWriteOutcome = WriteOutcome.Success
        if (configuredOutcome != WriteOutcome.Success) return configuredOutcome
        if (documents[name] != expected) return WriteOutcome.Conflict
        documents[name] = replacement
        return WriteOutcome.Success
    }

    fun put(name: String, text: String) {
        documents[name] = text
    }

    fun names(): List<String> = documents.keys.toList()

    fun text(name: String): String? = documents[name]
}
