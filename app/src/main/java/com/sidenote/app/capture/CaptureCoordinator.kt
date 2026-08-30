package com.sidenote.app.capture

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.markdown.ProjectSyntax
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import java.time.Clock
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CaptureCoordinator(
    private val repository: DocumentRepository,
    private val recovery: RecoveryDraftStore,
    private val saveMutex: Mutex,
    private val clock: Clock,
    private val zone: ZoneId,
    private val speech: SpeechControl,
    private val haptic: HapticConfirmation,
    private val closer: CaptureCloser,
) {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    private var terminal = false

    fun start(voiceDefaultOn: Boolean, recovered: RecoveryDraft?) {
        terminal = false
        mutableState.value = if (recovered == null) {
            CaptureState(voiceEnabled = voiceDefaultOn)
        } else {
            CaptureState(
                draft = TextFieldValue(recovered.text, recovered.selection),
                voiceEnabled = recovered.voiceEnabled,
            )
        }
    }

    fun onUserEdit(value: TextFieldValue) {
        val current = mutableState.value
        if (current.voiceEnabled) speech.stop()
        mutableState.value = current.copy(
            draft = value,
            voiceEnabled = false,
            speechOwnedRange = null,
            rms = 0f,
            status = CaptureStatus.Ready,
        )
    }

    fun onVoiceToggle() {
        val current = mutableState.value
        if (current.voiceEnabled) {
            speech.stop()
            mutableState.value = current.copy(
                voiceEnabled = false,
                speechOwnedRange = null,
                rms = 0f,
                status = CaptureStatus.Ready,
            )
        } else {
            mutableState.value = current.copy(
                voiceEnabled = true,
                status = CaptureStatus.Ready,
            )
        }
    }

    fun onSpeechPartial(text: String) {
        if (!mutableState.value.voiceEnabled) return
        replaceSpeechOwnedSpan(text, final = false)
    }

    fun onSpeechFinal(text: String, locale: Locale) {
        if (!mutableState.value.voiceEnabled) return
        val command = ProjectSyntax.extractVoiceCommand(text, locale)
        val visibleText = buildList {
            command.projects.forEach { project -> add("@$project") }
            if (command.text.isNotEmpty()) add(command.text)
        }.joinToString(" ")
        replaceSpeechOwnedSpan(visibleText, final = true)
    }

    fun onSpeechFailure() {
        val current = mutableState.value
        mutableState.value = current.copy(
            voiceEnabled = false,
            speechOwnedRange = null,
            rms = 0f,
            status = CaptureStatus.SpeechUnavailable,
        )
    }

    suspend fun complete(signal: CompletionSignal) {
        if (!saveMutex.tryLock()) return
        try {
            if (terminal) return
            val current = mutableState.value
            val text = current.draft.text
            if (text.isBlank()) {
                terminal = true
                recovery.clear()
                closer.close()
                return
            }

            if (current.voiceEnabled) speech.stop()
            mutableState.value = current.copy(
                voiceEnabled = false,
                speechOwnedRange = null,
                rms = 0f,
                status = CaptureStatus.Saving,
            )
            when (repository.append(text, clock.instant(), zone)) {
                AppendResult.Success -> {
                    terminal = true
                    recovery.clear()
                    mutableState.value = mutableState.value.copy(status = CaptureStatus.Saved)
                    haptic.confirm()
                    closer.close()
                }

                AppendResult.Conflict,
                is AppendResult.Failure,
                -> mutableState.value = mutableState.value.copy(status = CaptureStatus.SaveFailed)
            }
        } finally {
            saveMutex.unlock()
        }
    }

    suspend fun discard() {
        saveMutex.withLock {
            if (terminal) return@withLock
            terminal = true
            recovery.clear()
            mutableState.value = CaptureState(
                voiceEnabled = false,
                status = CaptureStatus.Discarded,
            )
            haptic.confirm()
            closer.close()
        }
    }

    private fun replaceSpeechOwnedSpan(replacement: String, final: Boolean) {
        val current = mutableState.value
        val sourceRange = current.speechOwnedRange ?: current.draft.selection
        val start = min(sourceRange.start, sourceRange.end).coerceIn(0, current.draft.text.length)
        val end = max(sourceRange.start, sourceRange.end).coerceIn(start, current.draft.text.length)
        val updatedText = current.draft.text.replaceRange(start, end, replacement)
        val cursor = start + replacement.length
        mutableState.value = current.copy(
            draft = TextFieldValue(updatedText, TextRange(cursor)),
            speechOwnedRange = if (final) null else TextRange(start, cursor),
            status = CaptureStatus.Ready,
        )
    }
}
