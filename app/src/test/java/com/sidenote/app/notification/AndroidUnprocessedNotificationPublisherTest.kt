package com.sidenote.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.MainActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPendingIntent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUnprocessedNotificationPublisherTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val shadowManager = shadowOf(manager)

    @Before
    fun resetNotificationState() {
        ShadowNotificationManager.reset()
        ShadowPendingIntent.reset()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
        )
        shadowManager.setNotificationsEnabled(true)
    }

    @Test
    fun adapterPostsExactChannelFlagsActionAndExplicitPendingIntent() {
        val outcome = AndroidUnprocessedNotificationPublisher(context).publish(spec(count = 3))

        assertThat(outcome).isEqualTo(NotificationPublishOutcome.Posted)
        val channel = manager.getNotificationChannel("unprocessed_notes")
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(manager.notificationChannels.map { it.id }).containsExactly("unprocessed_notes")

        val notification = shadowManager.getNotification(1001)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("3 unprocessed notes")
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Review")

        val pendingIntent = notification.actions.single().actionIntent
        val shadowPendingIntent = shadowOf(pendingIntent)
        assertThat(shadowPendingIntent.isActivity).isTrue()
        assertThat(shadowPendingIntent.isImmutable).isTrue()
        assertThat(shadowPendingIntent.flags and PendingIntent.FLAG_UPDATE_CURRENT).isNotEqualTo(0)
        assertThat(shadowPendingIntent.flags and PendingIntent.FLAG_MUTABLE).isEqualTo(0)
        assertThat(shadowPendingIntent.savedIntent.component?.className)
            .isEqualTo(MainActivity::class.java.name)
        assertThat(
            shadowPendingIntent.savedIntent.getBooleanExtra(
                MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED,
                false,
            ),
        ).isTrue()
        assertThat(notification.contentIntent).isEqualTo(pendingIntent)
    }

    @Test
    fun adapterTreatsDisabledNotificationsAsPermissionDenialWithoutPosting() {
        shadowManager.setNotificationsEnabled(false)

        val outcome = AndroidUnprocessedNotificationPublisher(context).publish(spec(count = 2))

        assertThat(outcome).isEqualTo(NotificationPublishOutcome.PermissionDenied)
        assertThat(shadowManager.allNotifications).isEmpty()
    }

    private fun spec(count: Int) = UnprocessedNotificationSpec(
        id = 1001,
        channelId = "unprocessed_notes",
        title = "$count unprocessed notes",
        ongoing = true,
        onlyAlertOnce = true,
        reviewActionTitle = "Review",
        destinationExtra = MainActivity.EXTRA_OPEN_MOST_RECENT_UNPROCESSED,
        destinationExtraValue = true,
    )
}
