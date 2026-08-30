package com.sidenote.app.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidSpeechEngine internal constructor(
    private val onlineFallbackAllowed: Boolean,
    private val sessionFactory: RecognitionSessionFactory,
    private val rmsAlpha: Float = DEFAULT_RMS_ALPHA,
) : SpeechEngine {
    constructor(
        context: Context,
        onlineFallbackAllowed: Boolean,
    ) : this(
        onlineFallbackAllowed = onlineFallbackAllowed,
        sessionFactory = AndroidRecognitionSessionFactory(context.applicationContext),
        rmsAlpha = DEFAULT_RMS_ALPHA,
    )

    private val lock = Any()
    private val attemptPolicy = SpeechAttemptPolicy(onlineFallbackAllowed)
    private val requestedDownloads = mutableSetOf<String>()
    private var active: ActiveSession? = null
    private var listener: SpeechEngine.Listener? = null
    private var generation = 0L
    private var destroyed = false
    private var rmsSmoother = RmsSmoother(rmsAlpha)

    override suspend fun support(): SpeechAvailability {
        if (isDestroyed()) return SpeechAvailability.TypedOnly

        val local = supportedLanguages(Attempt.Local)
        if (SpeechSupport.evaluate(local, emptySet(), fallbackAllowed = false) == SpeechAvailability.Available) {
            return SpeechAvailability.Available
        }

        val online = if (onlineFallbackAllowed) supportedLanguages(Attempt.Online) else emptySet()
        return SpeechSupport.evaluate(local, online, onlineFallbackAllowed)
    }

    override suspend fun requestModelDownloads() {
        if (isDestroyed()) return
        val session = createSessionOrNull(Attempt.Local) ?: return
        try {
            SpeechSupport.requiredLanguages.forEach { languageTag ->
                val request = SpeechRequest(preferOffline = true, languageTag = languageTag)
                val support = runCatching { session.checkSupport(request) }.getOrNull()
                    ?: return@forEach
                val shouldRequest =
                    languageTag in support.supportedOnDeviceLanguages &&
                        languageTag !in support.installedOnDeviceLanguages &&
                        languageTag !in support.pendingOnDeviceLanguages &&
                        synchronized(lock) { requestedDownloads.add(languageTag) }
                if (shouldRequest) {
                    runCatching { session.requestModelDownload(request) }
                }
            }
        } finally {
            destroySession(session)
        }
    }

    override fun start(listener: SpeechEngine.Listener) {
        val previous: RecognitionSession?
        val currentGeneration: Long
        synchronized(lock) {
            if (destroyed) return
            previous = active?.session
            active = null
            generation += 1
            currentGeneration = generation
            this.listener = listener
            rmsSmoother = RmsSmoother(rmsAlpha)
        }
        previous?.let(::cancelAndDestroySession)
        startAttempt(currentGeneration, Attempt.Local)
    }

    override fun stop() {
        val session = synchronized(lock) {
            if (destroyed) return
            generation += 1
            listener = null
            rmsSmoother = RmsSmoother(rmsAlpha)
            active?.session.also { active = null }
        }
        session?.let(::stopAndDestroySession)
    }

    override fun destroy() {
        val session = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            generation += 1
            listener = null
            active?.session.also { active = null }
        }
        session?.let(::cancelAndDestroySession)
    }

    private suspend fun supportedLanguages(attempt: Attempt): Set<String> {
        val session = createSessionOrNull(attempt) ?: return emptySet()
        return try {
            buildSet {
                SpeechSupport.requiredLanguages.forEach { languageTag ->
                    val support = runCatching {
                        session.checkSupport(
                            SpeechRequest(
                                preferOffline = attempt == Attempt.Local,
                                languageTag = languageTag,
                            ),
                        )
                    }.getOrNull() ?: return@forEach
                    val pathLanguages = if (attempt == Attempt.Local) {
                        support.supportedOnDeviceLanguages
                    } else {
                        support.onlineLanguages
                    }
                    if (languageTag in pathLanguages) add(languageTag)
                }
            }
        } finally {
            destroySession(session)
        }
    }

    private fun startAttempt(currentGeneration: Long, attempt: Attempt) {
        val session = createSessionOrNull(attempt)
        if (session == null) {
            finishUnavailableSession(
                currentGeneration = currentGeneration,
                attempt = attempt,
                session = null,
                failure = SpeechFailure.LanguageUnavailable,
            )
            return
        }

        val activeSession = ActiveSession(attempt, session)
        val accepted = synchronized(lock) {
            if (destroyed || generation != currentGeneration || active != null) {
                false
            } else {
                active = activeSession
                true
            }
        }
        if (!accepted) {
            destroySession(session)
            return
        }

        val callbacks = callbacksFor(currentGeneration, activeSession)
        runCatching {
            session.start(
                SpeechRequest(preferOffline = attempt == Attempt.Local),
                callbacks,
            )
        }.onFailure {
            finishUnavailableSession(
                currentGeneration = currentGeneration,
                attempt = attempt,
                session = session,
                failure = SpeechFailure.Client,
            )
        }
    }

    private fun callbacksFor(
        currentGeneration: Long,
        activeSession: ActiveSession,
    ): RecognitionSessionListener = object : RecognitionSessionListener {
        override fun onPartial(text: String) {
            currentListener(currentGeneration, activeSession.session)?.onPartial(text)
        }

        override fun onFinal(text: String) {
            val currentListener = synchronized(lock) {
                if (!isCurrent(currentGeneration, activeSession.session)) return
                active = null
                listener
            }
            destroySession(activeSession.session)
            currentListener?.onFinal(text)
        }

        override fun onRmsChanged(rmsDb: Float) {
            val update = synchronized(lock) {
                if (!isCurrent(currentGeneration, activeSession.session)) return
                listener to rmsSmoother.update(rmsDb)
            }
            update.first?.onRms(update.second)
        }

        override fun onDetectedLanguage(languageTag: String) {
            currentListener(currentGeneration, activeSession.session)
                ?.onDetectedLanguage(languageTag)
        }

        override fun onError(errorCode: Int) {
            finishUnavailableSession(
                currentGeneration = currentGeneration,
                attempt = activeSession.attempt,
                session = activeSession.session,
                failure = speechFailureForAndroidError(errorCode),
            )
        }
    }

    private fun finishUnavailableSession(
        currentGeneration: Long,
        attempt: Attempt,
        session: RecognitionSession?,
        failure: SpeechFailure,
    ) {
        val outcome = synchronized(lock) {
            if (destroyed || generation != currentGeneration) return
            if (session != null) {
                if (active?.session !== session) return
                active = null
            } else if (active != null) {
                return
            }
            FailureOutcome(
                nextAttempt = attemptPolicy.nextAfter(attempt, failure),
                listener = listener,
            )
        }
        session?.let(::destroySession)
        if (outcome.nextAttempt != null) {
            startAttempt(currentGeneration, outcome.nextAttempt)
        } else {
            outcome.listener?.onUnavailable(failure)
        }
    }

    private fun currentListener(
        currentGeneration: Long,
        session: RecognitionSession,
    ): SpeechEngine.Listener? = synchronized(lock) {
        if (isCurrent(currentGeneration, session)) listener else null
    }

    private fun isCurrent(currentGeneration: Long, session: RecognitionSession): Boolean =
        !destroyed && generation == currentGeneration && active?.session === session

    private fun isDestroyed(): Boolean = synchronized(lock) { destroyed }

    private fun createSessionOrNull(attempt: Attempt): RecognitionSession? =
        runCatching { sessionFactory.create(attempt) }.getOrNull()

    private fun stopAndDestroySession(session: RecognitionSession) {
        runCatching { session.stop() }
        destroySession(session)
    }

    private fun cancelAndDestroySession(session: RecognitionSession) {
        runCatching { session.cancel() }
        destroySession(session)
    }

    private fun destroySession(session: RecognitionSession) {
        runCatching { session.destroy() }
    }

    private data class ActiveSession(
        val attempt: Attempt,
        val session: RecognitionSession,
    )

    private data class FailureOutcome(
        val nextAttempt: Attempt?,
        val listener: SpeechEngine.Listener?,
    )

    private companion object {
        const val DEFAULT_RMS_ALPHA = 0.25f
    }
}

internal data class SpeechRequest(
    val preferOffline: Boolean,
    val languageTag: String? = null,
)

internal data class RecognitionSupportSnapshot(
    val supportedOnDeviceLanguages: Set<String> = emptySet(),
    val installedOnDeviceLanguages: Set<String> = emptySet(),
    val pendingOnDeviceLanguages: Set<String> = emptySet(),
    val onlineLanguages: Set<String> = emptySet(),
)

internal fun interface RecognitionSessionFactory {
    fun create(attempt: Attempt): RecognitionSession
}

internal interface RecognitionSession {
    fun start(request: SpeechRequest, listener: RecognitionSessionListener)

    fun stop()

    fun cancel()

    fun destroy()

    suspend fun checkSupport(request: SpeechRequest): RecognitionSupportSnapshot

    fun requestModelDownload(request: SpeechRequest)
}

internal interface RecognitionSessionListener {
    fun onPartial(text: String)

    fun onFinal(text: String)

    fun onRmsChanged(rmsDb: Float)

    fun onDetectedLanguage(languageTag: String)

    fun onError(errorCode: Int)
}

internal object SpeechIntentFactory {
    fun create(request: SpeechRequest): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
            )
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                arrayListOf("en-US", "he-IL"),
            )
            request.languageTag?.let { languageTag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
        }
}

internal fun speechFailureForAndroidError(errorCode: Int): SpeechFailure =
    when (errorCode) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechFailure.NetworkTimeout
        SpeechRecognizer.ERROR_NETWORK -> SpeechFailure.Network
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> SpeechFailure.Server
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure.LanguageUnavailable
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.Permission
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
        -> SpeechFailure.Client
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.Audio
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechFailure.NoMatch
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.SpeechTimeout
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechFailure.LanguageNotSupported
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechFailure.TooManyRequests
        else -> SpeechFailure.Unknown
    }

private class AndroidRecognitionSessionFactory(
    private val context: Context,
) : RecognitionSessionFactory {
    private val executor: Executor = context.mainExecutor

    override fun create(attempt: Attempt): RecognitionSession {
        val recognizer = when (attempt) {
            Attempt.Local -> {
                check(SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }

            Attempt.Online -> {
                check(SpeechRecognizer.isRecognitionAvailable(context))
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }
        return AndroidRecognitionSession(recognizer, executor)
    }
}

private class AndroidRecognitionSession(
    private val recognizer: SpeechRecognizer,
    private val executor: Executor,
) : RecognitionSession {
    private var listener: RecognitionSessionListener? = null

    init {
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    listener?.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listener?.onError(error)
                }

                override fun onResults(results: Bundle?) {
                    firstResult(results)?.let { text -> listener?.onFinal(text) }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let { text -> listener?.onPartial(text) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onLanguageDetection(results: Bundle) {
                    results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                        ?.let { languageTag -> listener?.onDetectedLanguage(languageTag) }
                }
            },
        )
    }

    override fun start(request: SpeechRequest, listener: RecognitionSessionListener) {
        this.listener = listener
        recognizer.startListening(SpeechIntentFactory.create(request))
    }

    override fun stop() {
        recognizer.stopListening()
    }

    override fun cancel() {
        recognizer.cancel()
    }

    override fun destroy() {
        listener = null
        recognizer.destroy()
    }

    override suspend fun checkSupport(request: SpeechRequest): RecognitionSupportSnapshot =
        suspendCancellableCoroutine { continuation ->
            recognizer.checkRecognitionSupport(
                SpeechIntentFactory.create(request),
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (continuation.isActive) {
                            continuation.resume(recognitionSupport.toSnapshot())
                        }
                    }

                    override fun onError(error: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(RecognitionSupportException(error))
                        }
                    }
                },
            )
        }

    override fun requestModelDownload(request: SpeechRequest) {
        recognizer.triggerModelDownload(SpeechIntentFactory.create(request))
    }

    private fun firstResult(results: Bundle?): String? =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
}

private fun RecognitionSupport.toSnapshot(): RecognitionSupportSnapshot =
    RecognitionSupportSnapshot(
        supportedOnDeviceLanguages = supportedOnDeviceLanguages.toSet(),
        installedOnDeviceLanguages = installedOnDeviceLanguages.toSet(),
        pendingOnDeviceLanguages = pendingOnDeviceLanguages.toSet(),
        onlineLanguages = onlineLanguages.toSet(),
    )

private class RecognitionSupportException(errorCode: Int) :
    IllegalStateException("Speech recognition support check failed: $errorCode")
