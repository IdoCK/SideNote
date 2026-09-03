package com.sidenote.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sidenote.app.data.documents.SafTreePermission
import com.sidenote.app.data.documents.TreePermissionOutcome
import com.sidenote.app.navigation.PermissionState
import com.sidenote.app.navigation.SideNoteMainViewModel
import com.sidenote.app.navigation.SideNoteNavHost
import com.sidenote.app.privacy.LockState
import com.sidenote.app.privacy.UnlockGate
import com.sidenote.app.review.ReviewViewModel
import com.sidenote.app.ui.theme.SideNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private lateinit var lockState: LockState
    private lateinit var mainViewModel: SideNoteMainViewModel
    private lateinit var reviewViewModel: ReviewViewModel
    private val permissionState = mutableStateOf(PermissionState())
    private val protectedContentReady = mutableStateOf(false)
    private val setupUnavailable = mutableStateOf(false)
    private var dismissRequested = false
    private var pendingMostRecentUnprocessed = false

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || !::mainViewModel.isInitialized) {
            return@registerForActivityResult
        }
        val grantedFlags = result.data?.flags ?: 0
        lifecycleScope.launch(Dispatchers.IO) {
            when (SafTreePermission.persist(contentResolver, uri, grantedFlags)) {
                TreePermissionOutcome.Success -> mainViewModel.onFolderSelected(uri)
                is TreePermissionOutcome.Failure -> mainViewModel.onFolderSelectionFailed()
            }
        }
    }
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (::mainViewModel.isInitialized && !lockState.locked.value) {
            refreshPermissionState()
            (application as SideNoteApplication).scheduleNotificationRecovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        container = (application as SideNoteApplication).container
        lockState = container.lockState()
        pendingMostRecentUnprocessed =
            savedInstanceState?.getBoolean(STATE_PENDING_UNPROCESSED, false) == true
        consumeNotificationDestination(intent)
        lockState.refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                lockState.locked.collect { locked ->
                    if (!locked) (application as SideNoteApplication).scheduleNotificationRecovery()
                }
            }
        }
        if (!lockState.locked.value) initializeProtectedContent()

        setContent {
            SideNoteTheme {
                val locked by lockState.locked.collectAsState()
                LaunchedEffect(locked) {
                    if (!locked) {
                        dismissRequested = false
                        setShowWhenLocked(false)
                        initializeProtectedContent()
                    }
                }
                if (locked) {
                    UnlockGate(onRetry = ::retryDismissal)
                    LaunchedEffect(Unit) {
                        setShowWhenLocked(true)
                        requestDismissalOnce()
                    }
                } else if (!protectedContentReady.value) {
                    UnlockGate()
                } else if (setupUnavailable.value) {
                    Text("Setup")
                } else {
                    ProtectedContent()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationDestination(intent)
        if (::lockState.isInitialized) {
            lockState.refresh()
            if (!lockState.locked.value) {
                routePendingDestination()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_UNPROCESSED, pendingMostRecentUnprocessed)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::lockState.isInitialized) return
        lockState.refresh()
        if (!lockState.locked.value && ::mainViewModel.isInitialized) refreshPermissionState()
    }

    @Composable
    private fun ProtectedContent() {
        val mainState by mainViewModel.state.collectAsState()
        val reviewState by reviewViewModel.state.collectAsState()
        val settings = mainState.settings
        LaunchedEffect(settings?.treeUri, settings?.onboardingComplete) {
            settings?.let { current ->
                reviewViewModel.onDocumentSourceObserved(
                    treeUri = current.treeUri?.toString(),
                    onboardingComplete = current.onboardingComplete,
                )
            }
        }
        SideNoteNavHost(
            mainState = mainState,
            reviewState = reviewState,
            permissions = permissionState.value,
            onChooseFolder = ::launchFolderPickerAfterUnlock,
            onRequestPermissions = ::requestPermissionsAfterUnlock,
            onContinueOnboarding = mainViewModel::continueOnboarding,
            onAcceptVoiceDisclosure = mainViewModel::acceptVoiceDisclosure,
            onVoiceOnAtLaunchChange = mainViewModel::setVoiceOnAtLaunch,
            onOnlineFallbackChange = mainViewModel::setOnlineFallbackAllowed,
            onCheckSpeech = mainViewModel::checkSpeechSupport,
            onDownloadModels = mainViewModel::requestModelDownloads,
            onShowDates = reviewViewModel::showDates,
            onShowProjects = reviewViewModel::showProjects,
            onPreviousDay = reviewViewModel::previousDay,
            onNextDay = reviewViewModel::nextDay,
            onToggleExpanded = reviewViewModel::toggleExpanded,
            onProcessedChange = reviewViewModel::setProcessed,
            onOpenProject = reviewViewModel::openProject,
            onOpenSourceDay = reviewViewModel::openSourceDay,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }

    private fun initializeProtectedContent() {
        if (protectedContentReady.value) {
            routePendingDestination()
            return
        }
        val dependencies = container.mainDependencies()
        if (dependencies == null) {
            setupUnavailable.value = true
            protectedContentReady.value = true
            return
        }
        mainViewModel = ViewModelProvider(
            this,
            SideNoteMainViewModel.Factory(dependencies),
        )[SideNoteMainViewModel::class.java]
        reviewViewModel = ViewModelProvider(
            this,
            ReviewViewModel.Factory(
                dependencies.repository,
                dependencies.ioDispatcher,
                dependencies.notificationRefresher,
            ),
        )[ReviewViewModel::class.java]
        refreshPermissionState()
        protectedContentReady.value = true
        routePendingDestination()
    }

    private fun consumeNotificationDestination(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_MOST_RECENT_UNPROCESSED, false) == true) {
            pendingMostRecentUnprocessed = true
            intent.removeExtra(EXTRA_OPEN_MOST_RECENT_UNPROCESSED)
        }
    }

    private fun routePendingDestination() {
        if (!pendingMostRecentUnprocessed || !::reviewViewModel.isInitialized || lockState.locked.value) {
            return
        }
        pendingMostRecentUnprocessed = false
        reviewViewModel.openMostRecentUnprocessed()
    }

    private fun requestDismissalOnce() {
        if (dismissRequested || !lockState.locked.value) return
        dismissRequested = true
        lockState.requestDismissKeyguard(this)
    }

    private fun retryDismissal() {
        if (!lockState.locked.value) return
        dismissRequested = false
        requestDismissalOnce()
    }

    private fun launchFolderPickerAfterUnlock() {
        lockState.refresh()
        if (lockState.locked.value) {
            mainViewModel.onProtectedActionBlocked()
            return
        }
        folderPicker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                SafTreePermission.REQUIRED_FLAGS or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            ),
        )
    }

    private fun requestPermissionsAfterUnlock() {
        lockState.refresh()
        if (lockState.locked.value) {
            mainViewModel.onProtectedActionBlocked()
            return
        }
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    private fun refreshPermissionState() {
        permissionState.value = PermissionState(
            microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            notificationsGranted =
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED &&
                    getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        )
    }

    companion object {
        const val EXTRA_OPEN_MOST_RECENT_UNPROCESSED = "open_most_recent_unprocessed"
        private const val STATE_PENDING_UNPROCESSED = "pending_most_recent_unprocessed"
    }
}
