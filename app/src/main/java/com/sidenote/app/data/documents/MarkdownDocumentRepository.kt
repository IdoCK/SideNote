package com.sidenote.app.data.documents

import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.NewCapture
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.markdown.RewriteResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MarkdownDocumentRepository(
    private val store: TextDocumentStore,
    private val codec: MarkdownCodec,
    private val mutex: Mutex,
) : DocumentRepository {
    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = mutex.withLock {
        val committedDateTime = committedAt.atZone(zone)
        val date = committedDateTime.toLocalDate()
        val fileName = "$date.md"
        val existing = try {
            store.read(fileName)
        } catch (error: DocumentStoreException) {
            return@withLock AppendResult.Failure(error.error)
        }
        val original = existing ?: codec.createDailyFile(date)
        val replacement = codec.appendEntry(
            original,
            NewCapture(committedDateTime.toLocalTime(), text),
        )

        when (val outcome = store.writeAtomically(fileName, existing, replacement)) {
            WriteOutcome.Success -> AppendResult.Success
            WriteOutcome.Conflict -> AppendResult.Conflict
            is WriteOutcome.Uncertain -> AppendResult.Uncertain(outcome.error)
            is WriteOutcome.Failure -> AppendResult.Failure(outcome.error)
        }
    }

    override suspend fun days(): List<ParsedDailyFile> = mutex.withLock {
        store.listNames()
            .mapNotNull(::dailyFile)
            .sortedBy { it.first }
            .mapNotNull { (date, fileName) ->
                store.read(fileName)?.let { raw -> codec.parse(date, raw) }
            }
    }

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = mutex.withLock {
        val current = try {
            store.read(fileName)
        } catch (error: DocumentStoreException) {
            return@withLock UpdateResult.Failure(error.error)
        }
        if (current == null || current != expectedRaw) return@withLock UpdateResult.Conflict

        val replacement = when (val rewrite = codec.rewriteProcessed(current, source, processed)) {
            RewriteResult.Conflict -> return@withLock UpdateResult.Conflict
            is RewriteResult.Updated -> rewrite.text
        }
        when (val outcome = store.writeAtomically(fileName, current, replacement)) {
            WriteOutcome.Success -> UpdateResult.Success
            WriteOutcome.Conflict -> UpdateResult.Conflict
            is WriteOutcome.Uncertain -> UpdateResult.Uncertain(outcome.error)
            is WriteOutcome.Failure -> UpdateResult.Failure(outcome.error)
        }
    }

    override suspend fun uncheckedCount(): Int =
        days().sumOf { day -> day.entries.count { !it.processed } }

    private fun dailyFile(name: String): Pair<LocalDate, String>? {
        val match = DAILY_FILE.matchEntire(name) ?: return null
        val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
        return date to name
    }

    private companion object {
        val DAILY_FILE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.md$")
    }
}
