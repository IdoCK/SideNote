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

    private val transitionLock = Any()
    private var terminal = false
    private var transitionVersion = 0L

    fun start(voiceDefaultOn: Boolean, recovered: RecoveryDraft?) {
        synchronized(transitionLock) {
            terminal = false
            publishState(
                if (recovered == null) {
                    CaptureState(voiceEnabled = voiceDefaultOn)
                } else {
                    CaptureState(
                        draft = TextFieldValue(recovered.text, recovered.selection),
                        voiceEnabled = recovered.voiceEnabled,
                    )
                },
            )
        }
    }

    fun onUserEdit(value: TextFieldValue) {
        val shouldStop = synchronized(transitionLock) {
            val current = mutableState.value
            if (!acceptsInput(current)) return
            publishState(
                current.copy(
                    draft = value,
                    voiceEnabled = false,
                    speechOwnedRange = null,
                    rms = 0f,
                    status = CaptureStatus.Ready,
                ),
            )
            current.voiceEnabled
        }
        if (shouldStop) speech.stop()
    }

    fun onVoiceToggle() {
        val shouldStop = synchronized(transitionLock) {
            val current = mutableState.value
            if (!acceptsInput(current)) return
            if (current.voiceEnabled) {
                publishState(
                    current.copy(
                        voiceEnabled = false,
                        speechOwnedRange = null,
                        rms = 0f,
                        status = CaptureStatus.Ready,
                    ),
                )
                true
            } else {
                publishState(
                    current.copy(
                        voiceEnabled = true,
                        status = CaptureStatus.Ready,
                    ),
                )
                false
            }
        }
        if (shouldStop) speech.stop()
    }

    fun onSpeechPartial(text: String) {
        synchronized(transitionLock) {
            if (!acceptsSpeech(mutableState.value)) return
            replaceSpeechOwnedSpan(text, final = false)
        }
    }

    fun onSpeechFinal(text: String, locale: Locale) {
        synchronized(transitionLock) {
            if (!acceptsSpeech(mutableState.value)) return
            val command = ProjectSyntax.extractVoiceCommand(text, locale)
            val visibleText = buildList {
                command.projects.forEach { project -> add("@$project") }
                if (command.text.isNotEmpty()) add(command.text)
            }.joinToString(" ")
            replaceSpeechOwnedSpan(visibleText, final = true)
        }
    }

    fun onSpeechRms(normalizedRms: Float) {
        synchronized(transitionLock) {
            val current = mutableState.value
            if (!acceptsSpeech(current)) return
            val clamped = normalizedRms
                .takeIf(Float::isFinite)
                ?.coerceIn(0f, 1f)
                ?: 0f
            if (clamped != current.rms) {
                publishState(current.copy(rms = clamped))
            }
        }
    }

    fun onSpeechStopped() {
        synchronized(transitionLock) {
            val current = mutableState.value
            if (current.rms != 0f) {
                publishState(current.copy(rms = 0f))
            }
        }
    }

    fun onSpeechFailure() {
        synchronized(transitionLock) {
            val current = mutableState.value
            if (!acceptsSpeech(current)) return
            publishState(
                current.copy(
                    voiceEnabled = false,
                    speechOwnedRange = null,
                    rms = 0f,
                    status = CaptureStatus.SpeechUnavailable,
                ),
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun complete(signal: CompletionSignal) {
        if (!saveMutex.tryLock()) return
        try {
            val preparation = synchronized(transitionLock) {
                if (terminal) return
                val current = mutableState.value
                val text = current.draft.text
                if (text.isBlank()) terminal = true
                publishState(
                    current.copy(
                        voiceEnabled = false,
                        speechOwnedRange = null,
                        rms = 0f,
                        status = if (text.isBlank()) current.status else CaptureStatus.Saving,
                    ),
                )
                SavePreparation(
                    text = text,
                    version = transitionVersion,
                    stopSpeech = current.voiceEnabled,
                )
            }

            if (preparation.stopSpeech) speech.stop()
            if (preparation.text.isBlank()) {
                recovery.clear()
                closer.close()
                return
            }

            when (repository.append(preparation.text, clock.instant(), zone)) {
                AppendResult.Success -> {
                    val exactSnapshot = synchronized(transitionLock) {
                        val current = mutableState.value
                        if (
                            terminal ||
                            transitionVersion != preparation.version ||
                            current.status != CaptureStatus.Saving ||
                            current.draft.text != preparation.text
                        ) {
                            false
                        } else {
                            terminal = true
                            publishState(current.copy(status = CaptureStatus.Saved))
                            true
                        }
                    }
                    if (!exactSnapshot) return
                    recovery.clear()
                    haptic.confirm()
                    closer.close()
                }

                AppendResult.Conflict,
                is AppendResult.Failure,
                -> synchronized(transitionLock) {
                    val current = mutableState.value
                    if (
                        !terminal &&
                        transitionVersion == preparation.version &&
                        current.status == CaptureStatus.Saving &&
                        current.draft.text == preparation.text
                    ) {
                        publishState(current.copy(status = CaptureStatus.SaveFailed))
                    }
                }
            }
        } finally {
            saveMutex.unlock()
        }
    }

    suspend fun discard() {
        saveMutex.withLock {
            val shouldStop = synchronized(transitionLock) {
                if (terminal) return@withLock
                val current = mutableState.value
                terminal = true
                publishState(
                    CaptureState(
                        voiceEnabled = false,
                        status = CaptureStatus.Discarded,
                    ),
                )
                current.voiceEnabled
            }
            if (shouldStop) speech.stop()
            recovery.clear()
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
        publishState(
            current.copy(
                draft = TextFieldValue(updatedText, TextRange(cursor)),
                speechOwnedRange = if (final) null else TextRange(start, cursor),
                rms = if (final) 0f else current.rms,
                status = CaptureStatus.Ready,
            ),
        )
    }

    private fun acceptsInput(state: CaptureState): Boolean =
        !terminal && state.status != CaptureStatus.Saving

    private fun acceptsSpeech(state: CaptureState): Boolean =
        acceptsInput(state) && state.voiceEnabled && state.status == CaptureStatus.Ready

    private fun publishState(state: CaptureState) {
        mutableState.value = state
        transitionVersion += 1
    }

    private data class SavePreparation(
        val text: String,
        val version: Long,
        val stopSpeech: Boolean,
    )
}
