package com.sidenote.app.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class CaptureAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CaptureAssistantSession(this)
}

internal class CaptureAssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Use the system assistant launch path, with no forwarding of screen context or extras.
        startAssistantActivity(captureIntent(context))
        finish()
    }
}
