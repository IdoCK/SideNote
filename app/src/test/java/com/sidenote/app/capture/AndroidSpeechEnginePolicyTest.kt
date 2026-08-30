package com.sidenote.app.capture

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechEnginePolicyTest {
    @Test
    fun localRecoverableFailureRetriesOnlineOnceWhenAllowed() = runTest {
        val policy = SpeechAttemptPolicy(onlineFallbackAllowed = true)

        assertThat(policy.nextAfter(Attempt.Local, SpeechFailure.Network)).isEqualTo(Attempt.Online)
        assertThat(policy.nextAfter(Attempt.Online, SpeechFailure.Network)).isNull()
    }

    @Test
    fun everyRecoverableLocalFailureMayUseTheConsentGatedFallback() {
        val policy = SpeechAttemptPolicy(onlineFallbackAllowed = true)

        listOf(
            SpeechFailure.NetworkTimeout,
            SpeechFailure.Network,
            SpeechFailure.Server,
            SpeechFailure.LanguageUnavailable,
        ).forEach { failure ->
            assertThat(policy.nextAfter(Attempt.Local, failure)).isEqualTo(Attempt.Online)
        }
    }

    @Test
    fun permissionAndClientFailuresNeverRetryOnline() {
        val policy = SpeechAttemptPolicy(onlineFallbackAllowed = true)

        assertThat(policy.nextAfter(Attempt.Local, SpeechFailure.Permission)).isNull()
        assertThat(policy.nextAfter(Attempt.Local, SpeechFailure.Client)).isNull()
    }

    @Test
    fun recoverableFailureDoesNotRetryWithoutExplicitFallbackConsent() {
        val policy = SpeechAttemptPolicy(onlineFallbackAllowed = false)

        assertThat(policy.nextAfter(Attempt.Local, SpeechFailure.Network)).isNull()
    }

    @Test
    fun androidErrorsMapToStableSpeechFailures() {
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
            .isEqualTo(SpeechFailure.NetworkTimeout)
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_NETWORK))
            .isEqualTo(SpeechFailure.Network)
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_SERVER))
            .isEqualTo(SpeechFailure.Server)
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
            .isEqualTo(SpeechFailure.Server)
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
            .isEqualTo(SpeechFailure.LanguageUnavailable)
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
            .isEqualTo(SpeechFailure.Permission)
        assertThat(speechFailureForAndroidError(SpeechRecognizer.ERROR_CLIENT))
            .isEqualTo(SpeechFailure.Client)
        assertThat(speechFailureForAndroidError(Int.MIN_VALUE))
            .isEqualTo(SpeechFailure.Unknown)
    }

    @Test
    fun localSupportMustContainBothConstrainedLanguages() {
        assertThat(
            SpeechSupport.evaluate(
                local = setOf("en-US", "he-IL"),
                online = emptySet(),
                fallbackAllowed = false,
            ),
        ).isEqualTo(SpeechAvailability.Available)

        assertThat(
            SpeechSupport.evaluate(
                local = setOf("en-US"),
                online = emptySet(),
                fallbackAllowed = false,
            ),
        ).isEqualTo(SpeechAvailability.TypedOnly)
    }

    @Test
    fun onlineSupportRequiresBothLanguagesAndFallbackConsent() {
        assertThat(
            SpeechSupport.evaluate(
                local = emptySet(),
                online = setOf("en-US", "he-IL"),
                fallbackAllowed = true,
            ),
        ).isEqualTo(SpeechAvailability.Available)

        assertThat(
            SpeechSupport.evaluate(
                local = emptySet(),
                online = setOf("en-US", "he-IL"),
                fallbackAllowed = false,
            ),
        ).isEqualTo(SpeechAvailability.TypedOnly)
    }

    @Test
    fun supportCannotBeAssembledByCombiningIncompleteRecognitionPaths() {
        assertThat(
            SpeechSupport.evaluate(
                local = setOf("en-US"),
                online = setOf("he-IL"),
                fallbackAllowed = true,
            ),
        ).isEqualTo(SpeechAvailability.TypedOnly)
    }

    @Test
    fun rmsValuesAreClampedAndExponentiallySmoothed() {
        val smoother = RmsSmoother(alpha = 0.5f)

        assertThat(smoother.update(100f)).isWithin(0.0001f).of(0.5f)
        assertThat(smoother.update(-100f)).isWithin(0.0001f).of(0.25f)
        assertThat(smoother.update(Float.NaN)).isWithin(0.0001f).of(0.125f)
    }

    @Test
    fun backgroundCaptureLifecycleMarshalsEveryRecognizerOperationToMain() {
        DedicatedSpeechMainThread().use { mainThread ->
            val operationThreadIds = Collections.synchronizedList(mutableListOf<Int>())
            val factory = FakeRecognitionSessionFactory(operationThreadIds = operationThreadIds)
            val engine = AndroidSpeechEngine(
                onlineFallbackAllowed = false,
                sessionFactory = factory,
                mainThread = mainThread,
            )
            val caller = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "speech-background-caller")
            }

            try {
                caller.submit {
                    engine.start(RecordingSpeechListener())
                    engine.start(RecordingSpeechListener())
                    engine.stop()
                    engine.start(RecordingSpeechListener())
                    engine.destroy()
                }.get()
            } finally {
                caller.shutdownNow()
            }

            assertThat(operationThreadIds).isNotEmpty()
            assertThat(operationThreadIds.toSet()).containsExactly(mainThread.threadIdentity)
        }
    }

    @Test
    fun backgroundSupportAndDownloadsMarshalEveryRecognizerOperationToMain() = runTest {
        DedicatedSpeechMainThread().use { mainThread ->
            val operationThreadIds = Collections.synchronizedList(mutableListOf<Int>())
            val localSupport = RecognitionSupportSnapshot(
                supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
                installedOnDeviceLanguages = setOf("en-US"),
            )
            val factory = FakeRecognitionSessionFactory(
                localSupport = localSupport,
                operationThreadIds = operationThreadIds,
            )
            val engine = AndroidSpeechEngine(
                onlineFallbackAllowed = false,
                sessionFactory = factory,
                mainThread = mainThread,
            )

            withContext(Dispatchers.Default) {
                engine.support()
                engine.requestModelDownloads()
            }

            assertThat(operationThreadIds).isNotEmpty()
            assertThat(operationThreadIds.toSet()).containsExactly(mainThread.threadIdentity)
        }
    }

    @Test
    fun recoverableLocalFailureCreatesExactlyOneOnlineSessionAndCleansBoth() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = true,
            sessionFactory = factory,
        )
        val listener = RecordingSpeechListener()

        engine.start(listener)
        val local = factory.created.single()
        local.emitFailure(SpeechRecognizer.ERROR_NETWORK)
        val online = factory.created.last()
        online.emitFailure(SpeechRecognizer.ERROR_NETWORK)

        assertThat(factory.created.map { it.attempt }).containsExactly(Attempt.Local, Attempt.Online).inOrder()
        assertThat(local.destroyCount).isEqualTo(1)
        assertThat(online.destroyCount).isEqualTo(1)
        assertThat(listener.failures).containsExactly(SpeechFailure.Network)
    }

    @Test
    fun nonRetryableLocalFailureNeverCreatesOnlineSession() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = true,
            sessionFactory = factory,
        )
        val listener = RecordingSpeechListener()

        engine.start(listener)
        factory.created.single().emitFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        assertThat(factory.created.map { it.attempt }).containsExactly(Attempt.Local)
        assertThat(listener.failures).containsExactly(SpeechFailure.Permission)
    }

    @Test
    fun stopInvalidatesCallbacksBeforeStoppingAndDestroyingTheSession() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = true,
            sessionFactory = factory,
        )
        val listener = RecordingSpeechListener()

        engine.start(listener)
        val local = factory.created.single()
        engine.stop()
        local.emitPartial("stale")
        local.emitRms(10f)
        local.emitFailure(SpeechRecognizer.ERROR_NETWORK)

        assertThat(local.stopCount).isEqualTo(1)
        assertThat(local.destroyCount).isEqualTo(1)
        assertThat(listener.partials).isEmpty()
        assertThat(listener.rmsValues).isEmpty()
        assertThat(listener.failures).isEmpty()
        assertThat(factory.created).hasSize(1)
    }

    @Test
    fun startingAgainDestroysThePreviousSessionAndRejectsItsCallbacks() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )
        val firstListener = RecordingSpeechListener()
        val secondListener = RecordingSpeechListener()

        engine.start(firstListener)
        val first = factory.created.single()
        engine.start(secondListener)
        val second = factory.created.last()
        first.emitPartial("old")
        second.emitPartial("new")

        assertThat(first.cancelCount).isEqualTo(1)
        assertThat(first.destroyCount).isEqualTo(1)
        assertThat(firstListener.partials).isEmpty()
        assertThat(secondListener.partials).containsExactly("new")
    }

    @Test
    fun engineForwardsSmoothedRmsAndLanguageOnlyFromTheCurrentSession() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
            rmsAlpha = 0.5f,
        )
        val listener = RecordingSpeechListener()

        engine.start(listener)
        val local = factory.created.single()
        local.emitRms(10f)
        local.emitRms(-2f)
        local.emitDetectedLanguage("he-IL")

        assertThat(listener.rmsValues[0]).isWithin(0.0001f).of(0.5f)
        assertThat(listener.rmsValues[1]).isWithin(0.0001f).of(0.25f)
        assertThat(listener.detectedLanguages).containsExactly("he-IL")
        assertThat(factory.created.map { it.attempt }).containsExactly(Attempt.Local)
    }

    @Test
    fun finalResultIsForwardedOnceThenTheSessionIsDestroyedAndStaleEventsAreIgnored() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )
        val listener = RecordingSpeechListener()

        engine.start(listener)
        val local = factory.created.single()
        local.emitFinal("hello שלום")
        local.emitPartial("stale")

        assertThat(listener.finals).containsExactly("hello שלום")
        assertThat(listener.partials).isEmpty()
        assertThat(local.destroyCount).isEqualTo(1)
    }

    @Test
    fun emptyTerminalResultsBecomeNoMatchAndRejectStaleCallbacks() {
        listOf<String?>(null, "", "   ").forEach { terminalText ->
            val factory = FakeRecognitionSessionFactory()
            val engine = AndroidSpeechEngine(
                onlineFallbackAllowed = false,
                sessionFactory = factory,
            )
            val listener = RecordingSpeechListener()

            engine.start(listener)
            val local = factory.created.single()
            local.emitTerminalResult(terminalText)
            local.emitPartial("stale")

            assertThat(listener.finals).isEmpty()
            assertThat(listener.failures).containsExactly(SpeechFailure.NoMatch)
            assertThat(listener.partials).isEmpty()
            assertThat(local.destroyCount).isEqualTo(1)
        }
    }

    @Test
    fun destroyCancelsAndDestroysTheActiveSessionExactlyOnce() {
        val factory = FakeRecognitionSessionFactory()
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )
        val listener = RecordingSpeechListener()

        engine.start(listener)
        val local = factory.created.single()
        engine.destroy()
        engine.destroy()
        local.emitPartial("stale")

        assertThat(local.cancelCount).isEqualTo(1)
        assertThat(local.destroyCount).isEqualTo(1)
        assertThat(listener.partials).isEmpty()
    }

    @Test
    fun supportQueriesBothLanguagesAndCleansTheProbeSession() = runTest {
        val localSupport = RecognitionSupportSnapshot(
            supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
            installedOnDeviceLanguages = setOf("en-US", "he-IL"),
        )
        val factory = FakeRecognitionSessionFactory(localSupport = localSupport)
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = true,
            sessionFactory = factory,
        )

        assertThat(engine.support()).isEqualTo(SpeechAvailability.Available)

        val probe = factory.created.single()
        assertThat(probe.supportRequests.map { it.languageTag })
            .containsExactly("en-US", "he-IL")
            .inOrder()
        assertThat(probe.destroyCount).isEqualTo(1)
    }

    @Test
    fun destroyDuringSupportOwnsProbeAndPreventsLaterLanguageOrOnlineChecks() = runTest {
        val firstSupport = CompletableDeferred<RecognitionSupportSnapshot>()
        val supportStarted = CompletableDeferred<Unit>()
        val localSupport = RecognitionSupportSnapshot(
            supportedOnDeviceLanguages = setOf("en-US"),
        )
        val onlineSupport = RecognitionSupportSnapshot(
            onlineLanguages = setOf("en-US", "he-IL"),
        )
        val factory = FakeRecognitionSessionFactory(
            localSupport = localSupport,
            onlineSupport = onlineSupport,
            firstLocalSupport = firstSupport,
            supportStarted = supportStarted,
        )
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = true,
            sessionFactory = factory,
        )

        val availability = async { engine.support() }
        supportStarted.await()
        val local = factory.created.single()
        engine.destroy()
        runCurrent()
        val completedByDestroy = availability.isCompleted
        val destroyCountBeforeOldCallback = local.destroyCount
        firstSupport.complete(localSupport)

        assertThat(availability.await()).isEqualTo(SpeechAvailability.TypedOnly)
        assertThat(completedByDestroy).isTrue()
        assertThat(destroyCountBeforeOldCallback).isEqualTo(1)
        assertThat(local.supportRequests.map { it.languageTag }).containsExactly("en-US")
        assertThat(factory.created.map { it.attempt }).containsExactly(Attempt.Local)
        assertThat(local.destroyCount).isEqualTo(1)
    }

    @Test
    fun destroyDuringModelSupportPreventsEveryLaterDownloadSideEffect() = runTest {
        val firstSupport = CompletableDeferred<RecognitionSupportSnapshot>()
        val supportStarted = CompletableDeferred<Unit>()
        val localSupport = RecognitionSupportSnapshot(
            supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
        )
        val factory = FakeRecognitionSessionFactory(
            localSupport = localSupport,
            firstLocalSupport = firstSupport,
            supportStarted = supportStarted,
        )
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )

        val preparation = async { engine.requestModelDownloads() }
        supportStarted.await()
        val local = factory.created.single()
        engine.destroy()
        runCurrent()
        val completedByDestroy = preparation.isCompleted
        val destroyCountBeforeOldCallback = local.destroyCount
        firstSupport.complete(localSupport)
        preparation.await()

        assertThat(completedByDestroy).isTrue()
        assertThat(destroyCountBeforeOldCallback).isEqualTo(1)
        assertThat(local.supportRequests.map { it.languageTag }).containsExactly("en-US")
        assertThat(local.downloadRequests).isEmpty()
        assertThat(local.destroyCount).isEqualTo(1)
    }

    @Test
    fun backgroundDownloadReleasesLifecycleLockBeforeMainCallbackStartAndDestroy() = runTest {
        DedicatedSpeechMainThread().use { mainThread ->
            val firstSupport = CompletableDeferred<RecognitionSupportSnapshot>()
            val supportStarted = CompletableDeferred<Unit>()
            val localSupport = RecognitionSupportSnapshot(
                supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
            )
            val factory = FakeRecognitionSessionFactory(
                localSupport = localSupport,
                firstLocalSupport = firstSupport,
                supportStarted = supportStarted,
            )
            val engine = AndroidSpeechEngine(
                onlineFallbackAllowed = false,
                sessionFactory = factory,
                mainThread = mainThread,
            )
            val firstListener = RecordingSpeechListener()

            engine.start(firstListener)
            val capture = factory.created.single()
            val preparation = async(Dispatchers.Default) {
                engine.requestModelDownloads()
            }
            supportStarted.await()
            mainThread.beforeNextBackgroundRun {
                capture.emitPartial("main callback")
                engine.start(RecordingSpeechListener())
                engine.destroy()
            }
            firstSupport.complete(localSupport)
            preparation.await()

            assertThat(firstListener.partials).containsExactly("main callback")
            assertThat(factory.created).hasSize(3)
            assertThat(factory.created.flatMap { it.downloadRequests }).isEmpty()
            assertThat(factory.created.all { it.destroyCount == 1 }).isTrue()
        }
    }

    @Test
    fun callerCancellationStopsAfterCurrentProbeAndIsRethrown() = runTest {
        val firstSupport = CompletableDeferred<RecognitionSupportSnapshot>()
        val supportStarted = CompletableDeferred<Unit>()
        val factory = FakeRecognitionSessionFactory(
            localSupport = RecognitionSupportSnapshot(
                supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
            ),
            firstLocalSupport = firstSupport,
            supportStarted = supportStarted,
        )
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )

        val availability = async { engine.support() }
        supportStarted.await()
        availability.cancel()
        var cancellation: CancellationException? = null
        try {
            availability.await()
        } catch (expected: CancellationException) {
            cancellation = expected
        }

        val local = factory.created.single()
        assertThat(cancellation).isNotNull()
        assertThat(local.supportRequests.map { it.languageTag }).containsExactly("en-US")
        assertThat(local.destroyCount).isEqualTo(1)
        assertThat(factory.created.map { it.attempt }).containsExactly(Attempt.Local)
    }

    @Test
    fun onlineSupportIsCheckedForBothLanguagesOnlyWhenConsentCanMakeItUsable() = runTest {
        val localSupport = RecognitionSupportSnapshot(
            supportedOnDeviceLanguages = setOf("en-US"),
        )
        val onlineSupport = RecognitionSupportSnapshot(
            onlineLanguages = setOf("en-US", "he-IL"),
        )
        val factory = FakeRecognitionSessionFactory(
            localSupport = localSupport,
            onlineSupport = onlineSupport,
        )
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = true,
            sessionFactory = factory,
        )

        assertThat(engine.support()).isEqualTo(SpeechAvailability.Available)

        assertThat(factory.created.map { it.attempt }).containsExactly(Attempt.Local, Attempt.Online).inOrder()
        assertThat(factory.created.last().supportRequests.map { it.languageTag })
            .containsExactly("en-US", "he-IL")
            .inOrder()
        assertThat(factory.created.all { it.destroyCount == 1 }).isTrue()
    }

    @Test
    fun supportedMissingModelsAreRequestedOncePerLanguageAcrossOnboardingCalls() = runTest {
        val localSupport = RecognitionSupportSnapshot(
            supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
            installedOnDeviceLanguages = setOf("en-US"),
        )
        val factory = FakeRecognitionSessionFactory(localSupport = localSupport)
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )

        engine.requestModelDownloads()
        engine.requestModelDownloads()

        assertThat(factory.created).hasSize(2)
        assertThat(factory.created.flatMap { session -> session.downloadRequests }.map { it.languageTag })
            .containsExactly("he-IL")
        assertThat(factory.created.all { it.destroyCount == 1 }).isTrue()
    }

    @Test
    fun installedPendingAndUnsupportedModelsNeverTriggerDownloads() = runTest {
        val localSupport = RecognitionSupportSnapshot(
            supportedOnDeviceLanguages = setOf("en-US", "he-IL"),
            installedOnDeviceLanguages = setOf("en-US"),
            pendingOnDeviceLanguages = setOf("he-IL"),
        )
        val factory = FakeRecognitionSessionFactory(localSupport = localSupport)
        val engine = AndroidSpeechEngine(
            onlineFallbackAllowed = false,
            sessionFactory = factory,
        )

        engine.requestModelDownloads()

        assertThat(factory.created.single().downloadRequests).isEmpty()
        assertThat(factory.created.single().destroyCount).isEqualTo(1)
    }
}

private class FakeRecognitionSessionFactory(
    private val localSupport: RecognitionSupportSnapshot = RecognitionSupportSnapshot(),
    private val onlineSupport: RecognitionSupportSnapshot = RecognitionSupportSnapshot(),
    private val operationThreadIds: MutableList<Int>? = null,
    private val firstLocalSupport: CompletableDeferred<RecognitionSupportSnapshot>? = null,
    private val supportStarted: CompletableDeferred<Unit>? = null,
) : RecognitionSessionFactory {
    val created = mutableListOf<FakeRecognitionSession>()

    override fun create(attempt: Attempt): RecognitionSession =
        FakeRecognitionSession(
            attempt = attempt,
            support = if (attempt == Attempt.Local) localSupport else onlineSupport,
            operationThreadIds = operationThreadIds,
            firstSupport = if (attempt == Attempt.Local) firstLocalSupport else null,
            supportStarted = if (attempt == Attempt.Local) supportStarted else null,
        ).also(created::add)
            .also { operationThreadIds?.add(System.identityHashCode(Thread.currentThread())) }
}

private class FakeRecognitionSession(
    val attempt: Attempt,
    private val support: RecognitionSupportSnapshot,
    private val operationThreadIds: MutableList<Int>? = null,
    private val firstSupport: CompletableDeferred<RecognitionSupportSnapshot>? = null,
    private val supportStarted: CompletableDeferred<Unit>? = null,
) : RecognitionSession {
    val supportRequests = mutableListOf<SpeechRequest>()
    val downloadRequests = mutableListOf<SpeechRequest>()
    var stopCount = 0
    var cancelCount = 0
    var destroyCount = 0
    private var listener: RecognitionSessionListener? = null

    override fun start(request: SpeechRequest, listener: RecognitionSessionListener) {
        operationThreadIds?.add(System.identityHashCode(Thread.currentThread()))
        this.listener = listener
    }

    override fun stop() {
        operationThreadIds?.add(System.identityHashCode(Thread.currentThread()))
        stopCount += 1
    }

    override fun cancel() {
        operationThreadIds?.add(System.identityHashCode(Thread.currentThread()))
        cancelCount += 1
    }

    override fun destroy() {
        operationThreadIds?.add(System.identityHashCode(Thread.currentThread()))
        destroyCount += 1
    }

    override suspend fun checkSupport(request: SpeechRequest): RecognitionSupportSnapshot {
        operationThreadIds?.add(System.identityHashCode(Thread.currentThread()))
        supportRequests += request
        if (supportRequests.size == 1 && firstSupport != null) {
            supportStarted?.complete(Unit)
            return firstSupport.await()
        }
        return support
    }

    override fun requestModelDownload(request: SpeechRequest) {
        operationThreadIds?.add(System.identityHashCode(Thread.currentThread()))
        downloadRequests += request
    }

    fun emitPartial(text: String) = listener?.onPartial(text) ?: Unit

    fun emitFinal(text: String) = listener?.onFinal(text) ?: Unit

    fun emitTerminalResult(text: String?) = listener?.onFinal(text.orEmpty()) ?: Unit

    fun emitRms(rmsDb: Float) = listener?.onRmsChanged(rmsDb) ?: Unit

    fun emitDetectedLanguage(languageTag: String) =
        listener?.onDetectedLanguage(languageTag) ?: Unit

    fun emitFailure(errorCode: Int) = listener?.onError(errorCode) ?: Unit
}

private class DedicatedSpeechMainThread : SpeechMainThread, AutoCloseable {
    val threadName = "speech-test-main"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName)
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val monitor = Any()
    private var beforeNextBackgroundRun: (() -> Unit)? = null
    val threadIdentity: Int = executor.submit<Int> {
        System.identityHashCode(Thread.currentThread())
    }.get()

    fun beforeNextBackgroundRun(action: () -> Unit) {
        synchronized(monitor) {
            check(beforeNextBackgroundRun == null)
            beforeNextBackgroundRun = action
        }
    }

    override fun <T> run(block: () -> T): T {
        if (Thread.currentThread().name == threadName) return block()
        val interleaving = synchronized(monitor) {
            beforeNextBackgroundRun.also { beforeNextBackgroundRun = null }
        }
        if (interleaving == null) return executor.submit<T>(block).get()

        val mainLifecycleWork = executor.submit(interleaving)
        val platformWork = executor.submit<T>(block)
        val result = try {
            platformWork.get(2, TimeUnit.SECONDS)
        } catch (exception: TimeoutException) {
            throw AssertionError(
                "Blocking main-thread speech work could not pass queued lifecycle work; " +
                    "the caller still holds the lifecycle lock",
                exception,
            )
        }
        try {
            mainLifecycleWork.get()
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
        return result
    }

    override suspend fun <T> runSuspending(block: suspend () -> T): T =
        withContext(dispatcher) { block() }

    override fun close() {
        dispatcher.close()
        executor.shutdownNow()
    }
}

private class RecordingSpeechListener : SpeechEngine.Listener {
    val partials = mutableListOf<String>()
    val finals = mutableListOf<String>()
    val rmsValues = mutableListOf<Float>()
    val detectedLanguages = mutableListOf<String>()
    val failures = mutableListOf<SpeechFailure>()

    override fun onPartial(text: String) {
        partials += text
    }

    override fun onFinal(text: String) {
        finals += text
    }

    override fun onRms(normalizedRms: Float) {
        rmsValues += normalizedRms
    }

    override fun onDetectedLanguage(languageTag: String) {
        detectedLanguages += languageTag
    }

    override fun onUnavailable(failure: SpeechFailure) {
        failures += failure
    }
}
