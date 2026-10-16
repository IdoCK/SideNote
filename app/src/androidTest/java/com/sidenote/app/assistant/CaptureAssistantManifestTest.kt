package com.sidenote.app.assistant

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class CaptureAssistantManifestTest {
    @Test fun assistantIsSystemBoundAndSupportsKeyguardWithoutReplacingRecognition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, CaptureAssistantService::class.java), PackageManager.GET_META_DATA,
        )
        assertThat(info.permission).isEqualTo(Manifest.permission.BIND_VOICE_INTERACTION)
        info.loadXmlMetaData(context.packageManager, VoiceInteractionService.SERVICE_META_DATA).use { xml ->
            while (xml.next() != XmlPullParser.START_TAG) { }
            val ns = "http://schemas.android.com/apk/res/android"
            assertThat(xml.name).isEqualTo("voice-interaction-service")
            assertThat(xml.getAttributeBooleanValue(ns, "supportsAssist", false)).isTrue()
            assertThat(xml.getAttributeBooleanValue(ns, "supportsLaunchVoiceAssistFromKeyguard", false)).isTrue()
            assertThat(xml.getAttributeValue(ns, "recognitionService")).isEqualTo("")
            assertThat(xml.getAttributeValue(ns, "sessionService"))
                .isEqualTo(CaptureAssistantSessionService::class.java.name)
        }
        val session = context.packageManager.getServiceInfo(
            ComponentName(context, CaptureAssistantSessionService::class.java), 0,
        )
        assertThat(session.permission).isEqualTo(Manifest.permission.BIND_VOICE_INTERACTION)
    }
}
