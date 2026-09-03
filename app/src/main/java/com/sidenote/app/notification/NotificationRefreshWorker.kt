package com.sidenote.app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.privacy.LockState
import kotlinx.coroutines.CancellationException

class NotificationRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? SideNoteApplication ?: return Result.failure()
        return try {
            when (
                KeyguardSafeNotificationRecovery(
                    lockState = application.container.lockState(),
                    delegate = application.container.notificationRefresher(),
                ).refresh()
            ) {
                NotificationRefreshResult.Unavailable -> Result.retry()
                NotificationRefreshResult.FolderPermissionLost -> Result.success(
                    workDataOf(OUTPUT_FOLDER_PERMISSION_LOST to true),
                )
                is NotificationRefreshResult.Posted,
                NotificationRefreshResult.Removed,
                NotificationRefreshResult.PermissionDenied,
                -> Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "refresh-unprocessed-notes"
        const val OUTPUT_FOLDER_PERMISSION_LOST = "folder_permission_lost"
    }
}

class KeyguardSafeNotificationRecovery(
    private val lockState: LockState,
    private val delegate: NotificationRefresher,
) : NotificationRefresher {
    override suspend fun refresh(): NotificationRefreshResult {
        lockState.refresh()
        if (lockState.locked.value) return NotificationRefreshResult.Unavailable
        return delegate.refresh()
    }
}

fun interface NotificationRefreshScheduler {
    fun enqueue()
}

class WorkManagerNotificationRefreshScheduler(
    context: Context,
    private val enqueueUniqueWork: (
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) -> Unit = { name, policy, request ->
        WorkManager.getInstance(context.applicationContext ?: context)
            .enqueueUniqueWork(name, policy, request)
    },
) : NotificationRefreshScheduler {
    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<NotificationRefreshWorker>()
            .addTag(NotificationRefreshWorker::class.java.name)
            .build()
        enqueueUniqueWork(
            NotificationRefreshWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

object NoOpNotificationRefreshScheduler : NotificationRefreshScheduler {
    override fun enqueue() = Unit
}
