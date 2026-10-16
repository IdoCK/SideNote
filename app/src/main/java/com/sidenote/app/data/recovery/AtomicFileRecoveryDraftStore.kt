package com.sidenote.app.data.recovery

import androidx.compose.ui.text.TextRange
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

class AtomicFileRecoveryDraftStore(
    private val recoveryFile: File,
) : RecoveryDraftStore {
    private val atomicFile = AtomicFile(recoveryFile)

    override suspend fun load(): RecoveryLoadResult {
        val serialized = try {
            val serialized = atomicFile.openRead().use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
            serialized
        } catch (_: FileNotFoundException) {
            return if (hasNoAtomicFileState()) {
                RecoveryLoadResult.Empty
            } else {
                RecoveryLoadResult.ReadFailure(RecoveryReadError.Unavailable)
            }
        } catch (_: IOException) {
            return RecoveryLoadResult.ReadFailure(RecoveryReadError.Unavailable)
        }

        return try {
            RecoveryLoadResult.Draft(serialized.toRecoveryDraft())
        } catch (_: JSONException) {
            corruptResult()
        } catch (_: IllegalArgumentException) {
            corruptResult()
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

    private fun corruptResult(): RecoveryLoadResult = quarantine()
        ?.let(RecoveryLoadResult::CorruptDraft)
        ?: RecoveryLoadResult.ReadFailure(RecoveryReadError.Unavailable)

    private fun quarantine(): File? {
        val parent = recoveryFile.parentFile ?: return null
        var candidate = File(parent, QUARANTINED_FILE_NAME)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(parent, "recovery-draft.corrupt-$suffix.json")
            suffix += 1
        }
        return candidate.takeIf(recoveryFile::renameTo)
    }

    private fun hasNoAtomicFileState(): Boolean =
        !recoveryFile.exists() &&
            !File(recoveryFile.path + ".new").exists() &&
            !File(recoveryFile.path + ".bak").exists()

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
