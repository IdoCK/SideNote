package com.sidenote.app

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import com.sidenote.app.capture.AndroidCompletionSignalSource
import com.sidenote.app.capture.AndroidSpeechEngine
import com.sidenote.app.capture.CaptureDependencies
import com.sidenote.app.capture.CaptureRecoveryHandoff
import com.sidenote.app.capture.SpeechEngine
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.DocumentStoreException
import com.sidenote.app.data.documents.MarkdownDocumentRepository
import com.sidenote.app.data.documents.RepositoryError
import com.sidenote.app.data.documents.SafTextDocumentStore
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.AtomicFileRecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.settings.DataStoreSettingsRepository
import com.sidenote.app.data.settings.SettingsRepository
import com.sidenote.app.notification.AndroidUnprocessedNotificationPublisher
import com.sidenote.app.notification.NoOpNotificationRefreshScheduler
import com.sidenote.app.notification.NotificationRefreshScheduler
import com.sidenote.app.notification.NotificationRefresher
import com.sidenote.app.notification.BackgroundNotificationRefresher
import com.sidenote.app.notification.UnavailableNotificationRefresher
import com.sidenote.app.notification.UnprocessedNotificationCoordinator
import com.sidenote.app.notification.WorkManagerNotificationRefreshScheduler
import com.sidenote.app.privacy.AndroidLockState
import com.sidenote.app.privacy.LockState
import com.sidenote.app.privacy.UnlockedLockState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

interface AppContainer {
    fun captureDependencies(): CaptureDependencies

    fun mainDependencies(): MainDependencies? = null

    fun notificationRefresher(): NotificationRefresher = UnavailableNotificationRefresher

    fun notificationRefreshScheduler(): NotificationRefreshScheduler =
        NoOpNotificationRefreshScheduler

    fun lockState(): LockState = UnlockedLockState
}

data class MainDependencies(
    val settings: SettingsRepository,
    val repository: DocumentRepository,
    val speechFactory: (onlineFallbackAllowed: Boolean) -> SpeechEngine,
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    val notificationRefresher: NotificationRefresher = UnavailableNotificationRefresher,
    val notificationRefreshScheduler: NotificationRefreshScheduler =
        NoOpNotificationRefreshScheduler,
)

class ProductionAppContainer(
    context: Context,
) : AppContainer {
    private val appContext = context.applicationContext ?: context
    private val settingsRepository: SettingsRepository =
        DataStoreSettingsRepository(appContext.settingsDataStore)
    private val recoveryStore: RecoveryDraftStore = AtomicFileRecoveryDraftStore(
        File(appContext.filesDir, "recovery-draft.json"),
    )
    private val documentRepository: DocumentRepository = SettingsBackedDocumentRepository(
        context = appContext,
        settings = settingsRepository,
    )
    private val notificationRefresher = UnprocessedNotificationCoordinator(
        repository = documentRepository,
        publisher = AndroidUnprocessedNotificationPublisher(appContext),
    )
    private val notificationRefreshScheduler =
        WorkManagerNotificationRefreshScheduler(appContext)
    private val lockState = AndroidLockState(appContext)
    private val captureProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureRecoveryHandoff = CaptureRecoveryHandoff(
        store = recoveryStore,
        processScope = captureProcessScope,
    )

    override fun captureDependencies(): CaptureDependencies = CaptureDependencies(
        settings = settingsRepository,
        repository = documentRepository,
        speechFactory = { onlineFallbackAllowed ->
            AndroidSpeechEngine(appContext, onlineFallbackAllowed)
        },
        completionSignals = AndroidCompletionSignalSource(appContext).signals,
        clock = Clock.systemUTC(),
        zone = ZoneId.systemDefault(),
        ioDispatcher = Dispatchers.IO,
        recoveryHandoff = captureRecoveryHandoff,
        notificationRefresher = BackgroundNotificationRefresher(notificationRefresher, captureProcessScope),
    )

    override fun mainDependencies(): MainDependencies = MainDependencies(
        settings = settingsRepository,
        repository = documentRepository,
        speechFactory = { onlineFallbackAllowed ->
            AndroidSpeechEngine(appContext, onlineFallbackAllowed)
        },
        ioDispatcher = Dispatchers.IO,
        notificationRefresher = notificationRefresher,
        notificationRefreshScheduler = notificationRefreshScheduler,
    )

    override fun notificationRefresher(): NotificationRefresher = notificationRefresher

    override fun notificationRefreshScheduler(): NotificationRefreshScheduler =
        notificationRefreshScheduler

    override fun lockState(): LockState = lockState
}

private class SettingsBackedDocumentRepository(
    private val context: Context,
    private val settings: SettingsRepository,
) : DocumentRepository {
    private val repositoryLock = Mutex()
    private var cachedUri: Uri? = null
    private var cachedRepository: DocumentRepository? = null

    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = currentRepository()
        ?.append(text, committedAt, zone)
        ?: AppendResult.Failure(RepositoryError.PermissionLost)

    override suspend fun days(): List<ParsedDailyFile> =
        currentRepository()?.days().orEmpty()

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = currentRepository()
        ?.setProcessed(source, fileName, expectedRaw, processed)
        ?: UpdateResult.Failure(RepositoryError.PermissionLost)

    override suspend fun uncheckedCount(): Int = currentRepository()?.uncheckedCount()
        ?: throw DocumentStoreException(RepositoryError.PermissionLost)

    private suspend fun currentRepository(): DocumentRepository? {
        val treeUri = settings.settings.first().treeUri ?: return null
        return repositoryLock.withLock {
            if (cachedUri != treeUri || cachedRepository == null) {
                cachedUri = treeUri
                cachedRepository = MarkdownDocumentRepository(
                    store = SafTextDocumentStore(context, treeUri),
                    codec = MarkdownCodec(),
                    mutex = Mutex(),
                )
            }
            cachedRepository
        }
    }
}
