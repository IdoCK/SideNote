package com.sidenote.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface CompletionSignalSource {
    val signals: Flow<CompletionSignal>
}

class AndroidCompletionSignalSource(
    context: Context,
) : CompletionSignalSource {
    private val appContext = context.applicationContext ?: context
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)

    override val signals: Flow<CompletionSignal> = callbackFlow {
        val detector = FaceDownDetector(
            threshold = FACE_DOWN_THRESHOLD,
            releaseThreshold = FACE_DOWN_RELEASE_THRESHOLD,
            debounce = FACE_DOWN_DEBOUNCE,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    trySend(CompletionSignal.ScreenOff)
                }
            }
        }
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                detector.onZ(event.values[Z_AXIS_INDEX], event.timestamp.nanoseconds)
                    ?.let(::trySend)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        var receiverRegistered = false
        var sensorRegistered = false
        try {
            appContext.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
                sensorRegistered = sensorManager.registerListener(
                    sensorListener,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL,
                )
            }
        } catch (error: Exception) {
            if (sensorRegistered) sensorManager?.unregisterListener(sensorListener)
            if (receiverRegistered) appContext.unregisterReceiver(receiver)
            close(error)
            return@callbackFlow
        }
        awaitClose {
            if (sensorRegistered) sensorManager?.unregisterListener(sensorListener)
            if (receiverRegistered) appContext.unregisterReceiver(receiver)
        }
    }

    private companion object {
        const val FACE_DOWN_THRESHOLD = -8.5f
        const val FACE_DOWN_RELEASE_THRESHOLD = -7.0f
        const val Z_AXIS_INDEX = 2
        val FACE_DOWN_DEBOUNCE = 750.milliseconds
    }
}

class FaceDownDetector(
    private val threshold: Float,
    private val releaseThreshold: Float,
    private val debounce: Duration,
) {
    private val mutableEvents = mutableListOf<CompletionSignal>()
    val events: List<CompletionSignal> get() = mutableEvents

    private var candidateSince: Duration? = null
    private var emitted = false

    init {
        require(threshold < releaseThreshold)
        require(!debounce.isNegative())
    }

    fun onZ(z: Float, elapsed: Duration): CompletionSignal? {
        if (emitted) {
            if (z > releaseThreshold) {
                emitted = false
                candidateSince = null
            }
            return null
        }

        if (z > threshold) {
            candidateSince = null
            return null
        }

        val startedAt = candidateSince
        if (startedAt == null || elapsed < startedAt) {
            candidateSince = elapsed
            return null
        }
        if (elapsed - startedAt < debounce) return null

        emitted = true
        candidateSince = null
        return CompletionSignal.FaceDown.also(mutableEvents::add)
    }
}
