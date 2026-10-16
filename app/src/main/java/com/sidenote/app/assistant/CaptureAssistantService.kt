package com.sidenote.app.assistant

import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.sidenote.app.capture.CaptureActivity

/** System-bound entry point only; no hotword, microphone, sensor or polling work. */
class CaptureAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
        )
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(captureIntent(this))
    }
}

internal fun captureIntent(context: Context) = Intent(context, CaptureActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
