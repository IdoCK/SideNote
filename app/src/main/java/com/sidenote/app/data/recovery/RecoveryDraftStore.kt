package com.sidenote.app.data.recovery

import androidx.compose.ui.text.TextRange
import java.io.File

data class RecoveryDraft(
    val text: String,
    val selection: TextRange,
    val voiceEnabled: Boolean,
)

sealed interface RecoveryLoadResult {
    data object Empty : RecoveryLoadResult

    data class Draft(val draft: RecoveryDraft) : RecoveryLoadResult

    data class CorruptDraft(val quarantinedFile: File) : RecoveryLoadResult

    data class ReadFailure(val error: RecoveryReadError) : RecoveryLoadResult
}

enum class RecoveryReadError {
    Unavailable,
}

interface RecoveryDraftStore {
    suspend fun load(): RecoveryLoadResult

    suspend fun save(draft: RecoveryDraft)

    suspend fun clear()
}
