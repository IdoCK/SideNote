package com.sidenote.app.notification

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.CaptureDependencies
import com.sidenote.app.privacy.LockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SideNoteApplication::class)
class NotificationWorkSchedulingTest {
    private val application = RuntimeEnvironment.getApplication() as SideNoteApplication
    private lateinit var originalContainer: AppContainer

    @Before
    fun rememberContainer() {
        originalContainer = application.container
    }

    @After
    fun restoreContainer() {
        application.installContainerForTesting(originalContainer)
    }

    @Test
    fun workManagerAdapterEnqueuesOneTimeWorkerUnderExactUniqueName() {
        val calls = mutableListOf<EnqueueCall>()
        val scheduler = WorkManagerNotificationRefreshScheduler(application) {
                name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest ->
            calls += EnqueueCall(name, policy, request)
        }

        scheduler.enqueue()

        val call = calls.single()
        assertThat(call.name).isEqualTo("refresh-unprocessed-notes")
        assertThat(call.policy).isEqualTo(ExistingWorkPolicy.REPLACE)
        assertThat(call.request.tags).contains(NotificationRefreshWorker::class.java.name)
    }

    @Test
    fun bootCompletedDelegatesExactlyOnceToUniqueRefreshScheduler() {
        val fake = RecordingNotificationRefreshScheduler()
        application.installContainerForTesting(SchedulingContainer(fake))

        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(application, Intent("com.sidenote.UNRELATED"))

        assertThat(fake.enqueueCalls).isEqualTo(1)
    }

    @Test
    fun appRecoveryEnqueuesOnlyAfterTheKeyguardIsActuallyUnlocked() {
        val scheduler = RecordingNotificationRefreshScheduler()
        val lockState = MutableTestLockState(initiallyLocked = true)
        application.installContainerForTesting(SchedulingContainer(scheduler, lockState))

        assertThat(application.scheduleNotificationRecovery()).isFalse()
        assertThat(scheduler.enqueueCalls).isEqualTo(0)

        lockState.unlock()
        assertThat(application.scheduleNotificationRecovery()).isTrue()
        assertThat(scheduler.enqueueCalls).isEqualTo(1)
    }

    @Test
    fun workerRecoveryGateNeverReadsProtectedNotesWhileLocked() = runTest {
        val lockState = MutableTestLockState(initiallyLocked = true)
        val refresher = RecordingNotificationRefresher()
        val gated = KeyguardSafeNotificationRecovery(lockState, refresher)

        assertThat(gated.refresh()).isEqualTo(NotificationRefreshResult.Unavailable)
        assertThat(refresher.refreshCalls).isEqualTo(0)

        lockState.unlock()
        assertThat(gated.refresh()).isEqualTo(NotificationRefreshResult.Removed)
        assertThat(refresher.refreshCalls).isEqualTo(1)
    }
}

private data class EnqueueCall(
    val name: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
)

private class RecordingNotificationRefreshScheduler : NotificationRefreshScheduler {
    var enqueueCalls: Int = 0

    override fun enqueue() {
        enqueueCalls += 1
    }
}

private class SchedulingContainer(
    private val scheduler: NotificationRefreshScheduler,
    private val testLockState: LockState? = null,
) : AppContainer {
    override fun captureDependencies(): CaptureDependencies = error("Capture is not used")

    override fun notificationRefreshScheduler(): NotificationRefreshScheduler = scheduler

    override fun lockState(): LockState = testLockState ?: super.lockState()
}

private class MutableTestLockState(initiallyLocked: Boolean) : LockState {
    private val mutableLocked = MutableStateFlow(initiallyLocked)
    override val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    override fun refresh() = Unit

    override fun requestDismissKeyguard(activity: Activity) = Unit

    fun unlock() {
        mutableLocked.value = false
    }
}

private class RecordingNotificationRefresher : NotificationRefresher {
    var refreshCalls = 0

    override suspend fun refresh(): NotificationRefreshResult {
        refreshCalls += 1
        return NotificationRefreshResult.Removed
    }
}
