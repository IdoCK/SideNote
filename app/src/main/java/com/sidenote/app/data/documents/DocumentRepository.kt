package com.sidenote.app.data.documents

import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import java.time.Instant
import java.time.ZoneId

interface DocumentRepository {
    suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult

    suspend fun days(): List<ParsedDailyFile>

    suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult

    suspend fun uncheckedCount(): Int
}

sealed interface RepositoryError {
    data object PermissionLost : RepositoryError

    data object WriteFailed : RepositoryError
}

sealed interface AppendResult {
    data object Success : AppendResult

    data object Conflict : AppendResult

    data class Failure(val error: RepositoryError) : AppendResult
}

sealed interface UpdateResult {
    data object Success : UpdateResult

    data object Conflict : UpdateResult

    data class Failure(val error: RepositoryError) : UpdateResult
}
