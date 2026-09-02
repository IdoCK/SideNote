package com.sidenote.app

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.sidenote.app.data.documents.SafTreePermission
import com.sidenote.app.data.documents.TreePermissionOutcome
import com.sidenote.app.navigation.PermissionState
import com.sidenote.app.navigation.SideNoteMainViewModel
import com.sidenote.app.navigation.SideNoteNavHost
import com.sidenote.app.review.ReviewViewModel
import com.sidenote.app.ui.theme.SideNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: SideNoteMainViewModel
    private val permissionState = mutableStateOf(PermissionState())

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
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
        refreshPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val dependencies = (application as SideNoteApplication).container.mainDependencies()
        if (dependencies == null) {
            setContent { SideNoteTheme { Text("Setup") } }
            return
        }
        mainViewModel = ViewModelProvider(
            this,
            SideNoteMainViewModel.Factory(dependencies),
        )[SideNoteMainViewModel::class.java]
        val reviewViewModel = ViewModelProvider(
            this,
            ReviewViewModel.Factory(dependencies.repository, dependencies.ioDispatcher),
        )[ReviewViewModel::class.java]
        refreshPermissionState()

        setContent {
            SideNoteTheme {
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
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mainViewModel.isInitialized) refreshPermissionState()
    }

    private fun launchFolderPickerAfterUnlock() {
        if (deviceLocked()) {
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
        if (deviceLocked()) {
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

    private fun deviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
}
