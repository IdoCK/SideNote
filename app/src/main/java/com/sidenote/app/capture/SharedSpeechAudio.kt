package com.sidenote.app.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One microphone stream feeds both recognition and visual analysis. No audio is saved. */
internal class SharedSpeechAudio(
    private val onFeatures: (SpeechAudioFeatures) -> Unit,
    private val onFailure: () -> Unit,
) : Closeable {
    private val pipe = ParcelFileDescriptor.createPipe()
    val source: ParcelFileDescriptor get() = pipe[0]
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    @Volatile private var lastFrameMillis = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (android.os.SystemClock.elapsedRealtime() - lastFrameMillis > 2000L) {
                onFailure()
            } else main.postDelayed(this, 500L)
        }
    }

    fun start() {
        check(!closed.get())
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0)
        val audio = try { AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 8192))
            .build()
        } catch (denied: SecurityException) {
            close()
            throw denied
        }
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED)
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        running.set(true)
        lastFrameMillis = android.os.SystemClock.elapsedRealtime()
        main.postDelayed(watchdog, 500L)
        Thread({
            val analyzer = SpeechAudioAnalyzer()
            val samples = ShortArray(1024)
            val bytes = ByteArray(2048)
            try {
                while (running.get()) {
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (!running.get()) break
                    check(count > 0 && audio.activeRecordingConfiguration?.isClientSilenced != true)
                    val features = analyzer.analyze(samples, count)
                    main.post { if (running.get()) onFeatures(features) }
                    for (index in 0 until count) {
                        bytes[index * 2] = samples[index].toByte()
                        bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
                    }
                    output.write(bytes, 0, count * 2)
                    lastFrameMillis = android.os.SystemClock.elapsedRealtime()
                }
            } catch (_: Exception) {
                if (running.get()) main.post { if (running.get()) onFailure() }
            } finally {
                runCatching { output.close() }
                runCatching { source.close() }
                // The worker owns release; close() unblocks both the read and the pipe write.
                runCatching { audio.release() }
            }
        }, "SideNote speech audio").apply { isDaemon = true; start() }
    }

    /** End the stream with EOF after any pending pipe write, allowing the recognizer to finalize. */
    fun finishInput() {
        running.set(false)
        main.removeCallbacks(watchdog)
        runCatching { recorder?.stop() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val wasRunning = running.getAndSet(false)
        main.removeCallbacks(watchdog)
        runCatching { recorder?.stop() }
        runCatching { source.close() }
        runCatching { output.close() }
        if (!wasRunning) runCatching { recorder?.release() }
        recorder = null
    }

    companion object { const val SAMPLE_RATE = 16000 }
}
