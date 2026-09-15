package com.sidenote.app.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.capture.CaptureActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureAssistantTest {
    @Test fun lockedInvocationOpensOnlyCaptureInItsExistingTask() {
        val service = Robolectric.buildService(CaptureAssistantService::class.java).create().get()
        service.onLaunchVoiceAssistFromKeyguard()
        val launch = shadowOf(service).nextStartedActivity
        assertThat(launch.component).isEqualTo(ComponentName(service, CaptureActivity::class.java))
        assertThat(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(launch.action).isNotEqualTo(CaptureActivity.ACTION_REVIEW)
        assertThat(launch.extras).isNull()
    }

}
