package com.sidenote.app.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidSpeechEngine internal constructor(
    private val onlineFallbackAllowed: Boolean,
    private val sessionFactory: RecognitionSessionFactory,
    private val rmsAlpha: Float = DEFAULT_RMS_ALPHA,
    private val mainThread: SpeechMainThread = DirectSpeechMainThread,
) : SpeechEngine {
    constructor(
        context: Context,
        onlineFallbackAllowed: Boolean,
    ) : this(
        onlineFallbackAllowed = onlineFallbackAllowed,
        sessionFactory = AndroidRecognitionSessionFactory(context.applicationContext),
        rmsAlpha = DEFAULT_RMS_ALPHA,
        mainThread = AndroidSpeechMainThread(),
    )

    private val lock = Any()
    private val attemptPolicy = SpeechAttemptPolicy(onlineFallbackAllowed)
    private val requestedDownloads = mutableSetOf<String>()
    private val probes = mutableSetOf<RecognitionSession>()
    private val destroyedSignal = CompletableDeferred<Unit>()
    private var active: ActiveSession? = null
    private var listener: SpeechEngine.Listener? = null
    private var generation = 0L
    private var destroyed = false
    private var rmsSmoother = RmsSmoother(rmsAlpha)
    private var finalization: CompletableDeferred<Unit>? = null
    private var firstAttempt: Attempt? = Attempt.Local
    private var onlineReady = true

    override suspend fun support(): SpeechAvailability {
        if (isDestroyed()) return SpeechAvailability.TypedOnly

        val local = supportedLanguages(Attempt.Local)
        if (isDestroyed()) return SpeechAvailability.TypedOnly
        val online = if (onlineFallbackAllowed) supportedLanguages(Attempt.Online) else emptySet()
        if (isDestroyed()) return SpeechAvailability.TypedOnly
        synchronized(lock) {
            onlineReady = online.containsAll(SpeechSupport.requiredLanguages)
            firstAttempt = when {
                local.containsAll(SpeechSupport.requiredLanguages) -> Attempt.Local
                onlineFallbackAllowed && onlineReady -> Attempt.Online
                else -> null
            }
        }
        return SpeechSupport.evaluate(local, online, onlineFallbackAllowed)
    }

    override suspend fun requestModelDownloads() {
        if (isDestroyed()) return
        val session = createOwnedProbe(Attempt.Local) ?: return
        try {
            SpeechSupport.requiredLanguages.forEach { languageTag ->
                currentCoroutineContext().ensureActive()
                if (!isOwnedProbe(session)) return@forEach
                val request = SpeechRequest(preferOffline = true, languageTag = languageTag)
                val support = checkSupportOrNull(session, request) ?: return@forEach
                currentCoroutineContext().ensureActive()
                if (!isOwnedProbe(session)) return@forEach
                val downloadNeeded =
                    languageTag in support.supportedOnDeviceLanguages &&
                        languageTag !in support.installedOnDeviceLanguages &&
                        languageTag !in support.pendingOnDeviceLanguages
                if (downloadNeeded) {
                    val reserved = synchronized(lock) {
                        !destroyed &&
                            session in probes &&
                            requestedDownloads.add(languageTag)
                    }
                    if (!reserved) return@forEach
                    try {
                        mainThread.run {
                            synchronized(lock) {
                                if (
                                    !destroyed &&
                                    session in probes &&
                                    languageTag in requestedDownloads
                                ) {
                                    session.requestModelDownload(request)
                                }
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        // The explicit onboarding caller may retry preparation with a new engine.
                    }
                }
            }
        } finally {
            releaseProbe(session)
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
        val attempt = synchronized(lock) { firstAttempt }
        if (attempt == null) listener.onUnavailable(SpeechFailure.LanguageNotSupported)
        else startAttempt(currentGeneration, attempt)
    }

    override fun stop() {
        val session = synchronized(lock) {
            if (destroyed) return
            generation += 1
            listener = null
            finalization?.complete(Unit)
            finalization = null
            rmsSmoother = RmsSmoother(rmsAlpha)
            active?.session.also { active = null }
        }
        session?.let(::cancelAndDestroySession)
    }

    override suspend fun finish() {
        val pending = synchronized(lock) {
            if (destroyed || active == null) return
            val done = CompletableDeferred<Unit>()
            finalization = done
            active!!.session to done
        }
        try {
            ignorePlatformFailure { mainThread.run { pending.first.stop() } }
            withTimeoutOrNull(1500L) { pending.second.await() }
        } finally {
            stop()
        }
    }

    override fun destroy() {
        val sessions = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            generation += 1
            listener = null
            finalization?.complete(Unit)
            finalization = null
            buildList {
                active?.session?.let(::add)
                addAll(probes)
            }.distinct().also {
                active = null
                probes.clear()
            }
        }
        destroyedSignal.complete(Unit)
        sessions.forEach(::cancelAndDestroySession)
    }

    private suspend fun supportedLanguages(attempt: Attempt): Set<String> {
        val session = createOwnedProbe(attempt) ?: return emptySet()
        return try {
            val supported = mutableSetOf<String>()
            for (languageTag in SpeechSupport.requiredLanguages) {
                currentCoroutineContext().ensureActive()
                if (!isOwnedProbe(session)) break
                val support = checkSupportOrNull(
                    session,
                    SpeechRequest(
                        preferOffline = attempt == Attempt.Local,
                        languageTag = languageTag,
                    ),
                ) ?: continue
                if (!isOwnedProbe(session)) break
                val pathLanguages = if (attempt == Attempt.Local) {
                    support.installedOnDeviceLanguages
                } else {
                    support.onlineLanguages
                }
                if (languageTag in pathLanguages) supported += languageTag
            }
            supported
        } finally {
            releaseProbe(session)
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
        try {
            mainThread.run {
                session.start(
                    SpeechRequest(preferOffline = attempt == Attempt.Local),
                    callbacks,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
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
            if (text.isBlank()) {
                finishUnavailableSession(
                    currentGeneration = currentGeneration,
                    attempt = activeSession.attempt,
                    session = activeSession.session,
                    failure = SpeechFailure.NoMatch,
                )
                return
            }
            val delivery = synchronized(lock) {
                if (!isCurrent(currentGeneration, activeSession.session)) return
                active = null
                listener to finalization
            }
            destroySession(activeSession.session)
            delivery.first?.onFinal(text)
            delivery.second?.complete(Unit)
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
                nextAttempt = if (finalization != null || !onlineReady) null
                    else attemptPolicy.nextAfter(attempt, failure),
                listener = listener,
                finalization = finalization,
            )
        }
        session?.let(::destroySession)
        if (outcome.nextAttempt != null) {
            startAttempt(currentGeneration, outcome.nextAttempt)
        } else {
            outcome.listener?.onUnavailable(failure)
            outcome.finalization?.complete(Unit)
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
        try {
            mainThread.run { sessionFactory.create(attempt) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    private fun createOwnedProbe(attempt: Attempt): RecognitionSession? {
        val session = createSessionOrNull(attempt) ?: return null
        val accepted = synchronized(lock) {
            if (destroyed) false else probes.add(session)
        }
        if (!accepted) {
            destroySession(session)
            return null
        }
        return session
    }

    private fun isOwnedProbe(session: RecognitionSession): Boolean = synchronized(lock) {
        !destroyed && session in probes
    }

    private fun releaseProbe(session: RecognitionSession) {
        val owned = synchronized(lock) { probes.remove(session) }
        if (owned) destroySession(session)
    }

    private suspend fun checkSupportOrNull(
        session: RecognitionSession,
        request: SpeechRequest,
    ): RecognitionSupportSnapshot? = try {
        coroutineScope {
            val check = async {
                mainThread.runSuspending { session.checkSupport(request) }
            }
            select {
                check.onAwait { it }
                destroyedSignal.onAwait {
                    throw EngineDestroyedException()
                }
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private fun cancelAndDestroySession(session: RecognitionSession) {
        try {
            ignorePlatformFailure { mainThread.run { session.cancel() } }
        } finally {
            destroySession(session)
        }
    }

    private fun destroySession(session: RecognitionSession) {
        ignorePlatformFailure { mainThread.run { session.destroy() } }
    }

    private inline fun ignorePlatformFailure(block: () -> Unit) {
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Session state is already detached; no further platform cleanup is available.
        }
    }

    private data class ActiveSession(
        val attempt: Attempt,
        val session: RecognitionSession,
    )

    private data class FailureOutcome(
        val nextAttempt: Attempt?,
        val listener: SpeechEngine.Listener?,
        val finalization: CompletableDeferred<Unit>?,
    )

    private companion object {
        const val DEFAULT_RMS_ALPHA = 0.25f
    }
}

private class EngineDestroyedException : IllegalStateException("Speech engine destroyed")

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

internal interface SpeechMainThread {
    fun <T> run(block: () -> T): T

    suspend fun <T> runSuspending(block: suspend () -> T): T
}

internal class AndroidSpeechMainThread(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : SpeechMainThread {
    private val dispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            Looper.myLooper() != handler.looper

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(handler.post(block)) { "Android main looper rejected speech work" }
        }
    }

    override fun <T> run(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "Android main looper rejected speech work" }
        return try {
            task.get()
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        }
    }

    override suspend fun <T> runSuspending(block: suspend () -> T): T {
        return withContext(dispatcher) { block() }
    }
}

private object DirectSpeechMainThread : SpeechMainThread {
    override fun <T> run(block: () -> T): T = block()

    override suspend fun <T> runSuspending(block: suspend () -> T): T = block()
}

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

internal fun terminalRecognitionText(results: Bundle?): String =
    results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()

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
                    listener?.onFinal(terminalRecognitionText(results))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    terminalRecognitionText(partialResults)
                        .takeIf(String::isNotBlank)
                        ?.let { text -> listener?.onPartial(text) }
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
