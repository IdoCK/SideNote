package com.sidenote.app.data.recovery

import androidx.compose.ui.text.TextRange
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject

class AtomicFileRecoveryDraftStore(
    private val recoveryFile: File,
) : RecoveryDraftStore {
    private val atomicFile = AtomicFile(recoveryFile)

    override suspend fun load(): RecoveryLoadResult {
        if (!recoveryFile.exists()) return RecoveryLoadResult.Empty

        return try {
            val serialized = atomicFile.openRead().use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
            RecoveryLoadResult.Draft(serialized.toRecoveryDraft())
        } catch (_: Exception) {
            RecoveryLoadResult.CorruptDraft(quarantine())
        }
    }

    override suspend fun save(draft: RecoveryDraft) {
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(draft.toJson().toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    override suspend fun clear() {
        atomicFile.delete()
    }

    private fun quarantine(): File {
        val quarantined = File(recoveryFile.parentFile, QUARANTINED_FILE_NAME)
        quarantined.delete()
        if (!recoveryFile.renameTo(quarantined)) {
            atomicFile.delete()
        }
        return quarantined
    }

    private fun String.toRecoveryDraft(): RecoveryDraft {
        val json = JSONObject(this)
        val text = json.getString("text")
        val selectionStart = json.getInt("selectionStart")
        val selectionEnd = json.getInt("selectionEnd")
        require(selectionStart in 0..text.length)
        require(selectionEnd in 0..text.length)
        return RecoveryDraft(
            text = text,
            selection = TextRange(selectionStart, selectionEnd),
            voiceEnabled = json.getBoolean("voiceEnabled"),
        )
    }

    private fun RecoveryDraft.toJson(): String = JSONObject()
        .put("text", text)
        .put("selectionStart", selection.start)
        .put("selectionEnd", selection.end)
        .put("voiceEnabled", voiceEnabled)
        .toString()

    private companion object {
        const val QUARANTINED_FILE_NAME = "recovery-draft.corrupt.json"
    }
}
