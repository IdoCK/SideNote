package com.sidenote.app.capture

interface SpeechEngine : SpeechControl {
    suspend fun support(): SpeechAvailability

    suspend fun requestModelDownloads()

    fun start(listener: Listener)

    override fun stop()

    fun destroy()

    interface Listener {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onRms(normalizedRms: Float)

        fun onDetectedLanguage(languageTag: String)

        fun onUnavailable(failure: SpeechFailure)
    }
}

enum class SpeechAvailability {
    Available,
    TypedOnly,
}

enum class SpeechFailure {
    NetworkTimeout,
    Network,
    Server,
    LanguageUnavailable,
    Permission,
    Client,
    Audio,
    NoMatch,
    SpeechTimeout,
    Busy,
    LanguageNotSupported,
    TooManyRequests,
    Unknown,
}

internal enum class Attempt {
    Local,
    Online,
}

internal class SpeechAttemptPolicy(
    private val onlineFallbackAllowed: Boolean,
) {
    fun nextAfter(attempt: Attempt, failure: SpeechFailure): Attempt? =
        if (
            attempt == Attempt.Local &&
            onlineFallbackAllowed &&
            failure in recoverableFailures
        ) {
            Attempt.Online
        } else {
            null
        }

    private companion object {
        val recoverableFailures = setOf(
            SpeechFailure.NetworkTimeout,
            SpeechFailure.Network,
            SpeechFailure.Server,
            SpeechFailure.LanguageUnavailable,
        )
    }
}

internal object SpeechSupport {
    val requiredLanguages = listOf("en-US", "he-IL")

    fun evaluate(
        local: Set<String>,
        online: Set<String>,
        fallbackAllowed: Boolean,
    ): SpeechAvailability =
        if (
            local.containsAll(requiredLanguages) ||
            (fallbackAllowed && online.containsAll(requiredLanguages))
        ) {
            SpeechAvailability.Available
        } else {
            SpeechAvailability.TypedOnly
        }
}

internal class RmsSmoother(
    private val alpha: Float = DEFAULT_ALPHA,
) {
    private var smoothed = 0f

    init {
        require(alpha in 0f..1f)
    }

    fun update(rmsDb: Float): Float {
        val finiteRms = if (rmsDb.isFinite()) rmsDb else MIN_RMS_DB
        val normalized = ((finiteRms - MIN_RMS_DB) / (MAX_RMS_DB - MIN_RMS_DB)).coerceIn(0f, 1f)
        smoothed += alpha * (normalized - smoothed)
        return smoothed.coerceIn(0f, 1f)
    }

    private companion object {
        const val DEFAULT_ALPHA = 0.25f
        const val MIN_RMS_DB = -2f
        const val MAX_RMS_DB = 10f
    }
}
