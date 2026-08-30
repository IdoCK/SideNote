package com.sidenote.app.data.recovery

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class RecoveryDraftStoreTest {
    private val temporaryDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryDirectories.forEach { directory ->
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoveryKeepsOneDraftAndClearsOnlyExplicitly() = runTest {
        val store = store()

        store.save(RecoveryDraft("typed טקסט", TextRange(9), voiceEnabled = false))

        assertThat(store.load()).isEqualTo(
            RecoveryLoadResult.Draft(
                RecoveryDraft("typed טקסט", TextRange(9), voiceEnabled = false),
            ),
        )
        store.clear()
        assertThat(store.load()).isEqualTo(RecoveryLoadResult.Empty)
    }

    @Test
    fun corruptDraftIsQuarantinedAndReportedWithoutCrashingCapture() = runTest {
        val recoveryFile = recoveryFile()
        recoveryFile.writeText("not valid json", StandardCharsets.UTF_8)
        val store = AtomicFileRecoveryDraftStore(recoveryFile)

        val result = store.load()

        assertThat(result).isInstanceOf(RecoveryLoadResult.CorruptDraft::class.java)
        val corrupt = result as RecoveryLoadResult.CorruptDraft
        assertThat(recoveryFile.exists()).isFalse()
        assertThat(corrupt.quarantinedFile).isEqualTo(
            File(checkNotNull(recoveryFile.parentFile), "recovery-draft.corrupt.json"),
        )
        assertThat(corrupt.quarantinedFile.exists()).isTrue()
    }

    @Test
    fun interruptedAtomicWriteRecoversBackupWhenBaseFileIsMissing() = runTest {
        val recoveryFile = recoveryFile()
        val backup = File(recoveryFile.path + ".bak")
        backup.writeText(
            """{"text":"recovered","selectionStart":9,"selectionEnd":9,"voiceEnabled":true}""",
            StandardCharsets.UTF_8,
        )
        val store = AtomicFileRecoveryDraftStore(recoveryFile)

        assertThat(store.load()).isEqualTo(
            RecoveryLoadResult.Draft(
                RecoveryDraft("recovered", TextRange(9), voiceEnabled = true),
            ),
        )
        assertThat(recoveryFile.exists()).isTrue()
        assertThat(backup.exists()).isFalse()
    }

    @Test
    fun invalidSelectionBoundsAreQuarantinedAsCorruption() = runTest {
        val recoveryFile = recoveryFile()
        recoveryFile.writeText(
            """{"text":"x","selectionStart":2,"selectionEnd":2,"voiceEnabled":true}""",
            StandardCharsets.UTF_8,
        )

        val result = AtomicFileRecoveryDraftStore(recoveryFile).load()

        assertThat(result).isInstanceOf(RecoveryLoadResult.CorruptDraft::class.java)
        assertThat((result as RecoveryLoadResult.CorruptDraft).quarantinedFile.exists()).isTrue()
    }

    @Test
    fun readFailureIsReportedWithoutDeletingTheOriginalDraft() = runTest {
        val recoveryFile = recoveryFile()
        recoveryFile.mkdirs()

        val result = AtomicFileRecoveryDraftStore(recoveryFile).load()

        assertThat(result).isInstanceOf(RecoveryLoadResult.ReadFailure::class.java)
        assertThat(recoveryFile.exists()).isTrue()
    }

    private fun store(): RecoveryDraftStore = AtomicFileRecoveryDraftStore(recoveryFile())

    private fun recoveryFile(): File {
        val directory = Files.createTempDirectory("sidenote-recovery").toFile()
        temporaryDirectories += directory
        return File(directory, "recovery-draft.json")
    }
}
