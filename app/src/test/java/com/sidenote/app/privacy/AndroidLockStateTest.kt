package com.sidenote.app.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyguardManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidLockStateTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(KeyguardManager::class.java)
    private val shadowManager = shadowOf(manager)

    @Before
    fun resetKeyguard() {
        ShadowKeyguardManager.reset()
    }

    @Test
    fun realAdapterRequestsDismissalAndPublishesSuccess() {
        shadowManager.setIsDeviceLocked(true)
        shadowManager.setKeyguardLocked(true)
        val lockState = AndroidLockState(context)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        lockState.refresh()
        lockState.requestDismissKeyguard(activity)
        shadowManager.setIsDeviceLocked(false)
        shadowManager.setKeyguardLocked(false)

        assertThat(lockState.locked.value).isFalse()
    }

    @Test
    fun realAdapterCancellationRemainsLocked() {
        shadowManager.setIsDeviceLocked(true)
        shadowManager.setKeyguardLocked(true)
        val lockState = AndroidLockState(context)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        lockState.requestDismissKeyguard(activity)
        shadowManager.setKeyguardLocked(true)

        assertThat(lockState.locked.value).isTrue()
    }
}
