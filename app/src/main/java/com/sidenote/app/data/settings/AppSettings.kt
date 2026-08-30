package com.sidenote.app.data.settings

import android.net.Uri

data class AppSettings(
    val treeUri: Uri?,
    val voiceOnAtLaunch: Boolean = true,
    val onlineFallbackAllowed: Boolean = false,
    val onboardingComplete: Boolean = false,
)
