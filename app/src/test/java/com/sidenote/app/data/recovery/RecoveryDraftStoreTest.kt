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
        assertThat(recoveryFile.exists()).isFalse()
        assertThat(checkNotNull(recoveryFile.parentFile).listFiles().orEmpty().map(File::getName))
            .contains("recovery-draft.corrupt.json")
    }

    private fun store(): RecoveryDraftStore = AtomicFileRecoveryDraftStore(recoveryFile())

    private fun recoveryFile(): File {
        val directory = Files.createTempDirectory("sidenote-recovery").toFile()
        temporaryDirectories += directory
        return File(directory, "recovery-draft.json")
    }
}
