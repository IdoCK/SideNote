package com.sidenote.app.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A committed capture can close while its finite reminder refresh finishes in the process. */
class BackgroundNotificationRefresher(
    private val delegate: NotificationRefresher,
    private val processScope: CoroutineScope,
) : NotificationRefresher {
    override suspend fun refresh(): NotificationRefreshResult {
        processScope.launch {
            try {
                delegate.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The note is already durable; normal unlock/startup recovery retries reminders.
            }
        }
        return NotificationRefreshResult.Scheduled
    }
}
