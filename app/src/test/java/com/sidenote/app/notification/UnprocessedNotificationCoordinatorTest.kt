package com.sidenote.app.notification

import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.DocumentStoreException
import com.sidenote.app.data.documents.RepositoryError
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnprocessedNotificationCoordinatorTest {
    private val repository = CountingRepository()
    private val publisher = RecordingNotificationPublisher()
    private val coordinator = UnprocessedNotificationCoordinator(repository, publisher)

    @Test
    fun positiveCountPostsExactOngoingReviewNotificationFromFreshRepositoryCount() = runTest {
        repository.count = 3

        assertThat(coordinator.refresh()).isEqualTo(NotificationRefreshResult.Posted(3))
        repository.count = 4
        assertThat(coordinator.refresh()).isEqualTo(NotificationRefreshResult.Posted(4))

        assertThat(repository.uncheckedCountCalls).isEqualTo(2)
        assertThat(publisher.notifications).containsExactly(
            UnprocessedNotificationSpec(
                id = 1001,
                channelId = "unprocessed_notes",
                title = "3 unprocessed notes",
                ongoing = true,
                onlyAlertOnce = true,
                reviewActionTitle = "Review",
                destinationExtra = "open_most_recent_unprocessed",
                destinationExtraValue = true,
            ),
            UnprocessedNotificationSpec(
                id = 1001,
                channelId = "unprocessed_notes",
                title = "4 unprocessed notes",
                ongoing = true,
                onlyAlertOnce = true,
                reviewActionTitle = "Review",
                destinationExtra = "open_most_recent_unprocessed",
                destinationExtraValue = true,
            ),
        ).inOrder()
    }

    @Test
    fun zeroCountRemovesNotification() = runTest {
        repository.count = 0

        assertThat(coordinator.refresh()).isEqualTo(NotificationRefreshResult.Removed)

        assertThat(publisher.cancelledIds).containsExactly(1001)
        assertThat(publisher.notifications).isEmpty()
    }

    @Test
    fun deniedNotificationPermissionIsANonFatalResult() = runTest {
        repository.count = 2
        publisher.outcome = NotificationPublishOutcome.PermissionDenied

        assertThat(coordinator.refresh()).isEqualTo(NotificationRefreshResult.PermissionDenied)

        assertThat(publisher.notifications).hasSize(1)
    }

    @Test
    fun lostFolderPermissionBecomesRecoverableSettingsResultAndRemovesStaleCount() = runTest {
        repository.error = DocumentStoreException(RepositoryError.PermissionLost)

        assertThat(coordinator.refresh()).isEqualTo(NotificationRefreshResult.FolderPermissionLost)

        assertThat(publisher.cancelledIds).containsExactly(1001)
        assertThat(publisher.notifications).isEmpty()
    }

    @Test
    fun olderCountCannotPublishAfterANewerRefreshRemovesTheNotification() = runBlocking {
        val countCall = AtomicInteger()
        val publishStarted = CountDownLatch(1)
        val releaseOlderPublish = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val interleavedRepository = object : DocumentRepository by repository {
            override suspend fun uncheckedCount(): Int =
                if (countCall.incrementAndGet() == 1) 1 else 0
        }
        val interleavedPublisher = object : UnprocessedNotificationPublisher {
            override fun publish(
                notification: UnprocessedNotificationSpec,
            ): NotificationPublishOutcome {
                publishStarted.countDown()
                check(releaseOlderPublish.await(5, TimeUnit.SECONDS))
                events += "publish:${notification.title}"
                return NotificationPublishOutcome.Posted
            }

            override fun cancel(notificationId: Int) {
                events += "cancel:$notificationId"
            }
        }
        val interleavedCoordinator = UnprocessedNotificationCoordinator(
            interleavedRepository,
            interleavedPublisher,
        )

        coroutineScope {
            val older = async(Dispatchers.Default) { interleavedCoordinator.refresh() }
            check(publishStarted.await(5, TimeUnit.SECONDS))
            val newer = async(start = CoroutineStart.UNDISPATCHED) {
                interleavedCoordinator.refresh()
            }
            releaseOlderPublish.countDown()

            assertThat(older.await()).isEqualTo(NotificationRefreshResult.Posted(1))
            assertThat(newer.await()).isEqualTo(NotificationRefreshResult.Removed)
        }

        assertThat(events).containsExactly(
            "publish:1 unprocessed notes",
            "cancel:1001",
        ).inOrder()
    }
}

private class CountingRepository : DocumentRepository {
    var count: Int = 0
    var uncheckedCountCalls: Int = 0
    var error: DocumentStoreException? = null

    override suspend fun uncheckedCount(): Int {
        uncheckedCountCalls += 1
        error?.let { throw it }
        return count
    }

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult =
        error("Notification refresh must not append")

    override suspend fun days(): List<ParsedDailyFile> =
        error("Notification refresh must call uncheckedCount directly")

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Notification refresh must not update notes")
}

private class RecordingNotificationPublisher : UnprocessedNotificationPublisher {
    val notifications = mutableListOf<UnprocessedNotificationSpec>()
    val cancelledIds = mutableListOf<Int>()
    var outcome: NotificationPublishOutcome = NotificationPublishOutcome.Posted

    override fun publish(notification: UnprocessedNotificationSpec): NotificationPublishOutcome {
        notifications += notification
        return outcome
    }

    override fun cancel(notificationId: Int) {
        cancelledIds += notificationId
    }
}
