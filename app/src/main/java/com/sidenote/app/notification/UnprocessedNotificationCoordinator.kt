package com.sidenote.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.sidenote.app.MainActivity
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.DocumentStoreException
import com.sidenote.app.data.documents.RepositoryError
import kotlinx.coroutines.CancellationException

fun interface NotificationRefresher {
    suspend fun refresh(): NotificationRefreshResult
}

sealed interface NotificationRefreshResult {
    data class Posted(val count: Int) : NotificationRefreshResult

    data object Removed : NotificationRefreshResult

    data object PermissionDenied : NotificationRefreshResult

    data object FolderPermissionLost : NotificationRefreshResult

    data object Unavailable : NotificationRefreshResult
}

enum class NotificationPublishOutcome {
    Posted,
    PermissionDenied,
}

data class UnprocessedNotificationSpec(
    val id: Int,
    val channelId: String,
    val title: String,
    val ongoing: Boolean,
    val onlyAlertOnce: Boolean,
    val reviewActionTitle: String,
    val destinationExtra: String,
    val destinationExtraValue: Boolean,
)

interface UnprocessedNotificationPublisher {
    fun publish(notification: UnprocessedNotificationSpec): NotificationPublishOutcome

    fun cancel(notificationId: Int)
}

class UnprocessedNotificationCoordinator(
    private val repository: DocumentRepository,
    private val publisher: UnprocessedNotificationPublisher,
) : NotificationRefresher {
    override suspend fun refresh(): NotificationRefreshResult {
        val count = try {
            repository.uncheckedCount()
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentStoreException) {
            if (error.error == RepositoryError.PermissionLost) {
                publisher.cancel(NOTIFICATION_ID)
                return NotificationRefreshResult.FolderPermissionLost
            }
            return NotificationRefreshResult.Unavailable
        } catch (_: Exception) {
            return NotificationRefreshResult.Unavailable
        }

        if (count == 0) {
            publisher.cancel(NOTIFICATION_ID)
            return NotificationRefreshResult.Removed
        }

        val notification = UnprocessedNotificationSpec(
            id = NOTIFICATION_ID,
            channelId = CHANNEL_ID,
            title = "$count unprocessed notes",
            ongoing = true,
            onlyAlertOnce = true,
            reviewActionTitle = REVIEW_ACTION_TITLE,
            destinationExtra = DESTINATION_EXTRA,
            destinationExtraValue = true,
        )
        return when (publisher.publish(notification)) {
            NotificationPublishOutcome.Posted -> NotificationRefreshResult.Posted(count)
            NotificationPublishOutcome.PermissionDenied ->
                NotificationRefreshResult.PermissionDenied
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "unprocessed_notes"
        const val DESTINATION_EXTRA = "open_most_recent_unprocessed"
        private const val REVIEW_ACTION_TITLE = "Review"
    }
}

class AndroidUnprocessedNotificationPublisher(
    context: Context,
) : UnprocessedNotificationPublisher {
    private val appContext = context.applicationContext ?: context
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    override fun publish(notification: UnprocessedNotificationSpec): NotificationPublishOutcome {
        createChannel(notification.channelId)
        if (
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED ||
            !notificationManager.areNotificationsEnabled()
        ) {
            return NotificationPublishOutcome.PermissionDenied
        }

        val reviewIntent = Intent(appContext, MainActivity::class.java)
            .putExtra(notification.destinationExtra, notification.destinationExtraValue)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val reviewPendingIntent = PendingIntent.getActivity(
            appContext,
            notification.id,
            reviewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built = Notification.Builder(appContext, notification.channelId)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(notification.title)
            .setContentIntent(reviewPendingIntent)
            .setOngoing(notification.ongoing)
            .setOnlyAlertOnce(notification.onlyAlertOnce)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(
                    null,
                    notification.reviewActionTitle,
                    reviewPendingIntent,
                ).build(),
            )
            .build()
        return try {
            notificationManager.notify(notification.id, built)
            NotificationPublishOutcome.Posted
        } catch (_: SecurityException) {
            NotificationPublishOutcome.PermissionDenied
        }
    }

    override fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    private fun createChannel(channelId: String) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private companion object {
        const val CHANNEL_NAME = "Unprocessed notes"
    }
}

object UnavailableNotificationRefresher : NotificationRefresher {
    override suspend fun refresh(): NotificationRefreshResult = NotificationRefreshResult.Unavailable
}
