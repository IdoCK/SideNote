package com.sidenote.app

import android.app.Application
import androidx.annotation.VisibleForTesting

class SideNoteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainer(this)
        scheduleNotificationRecovery()
    }

    fun scheduleNotificationRecovery(): Boolean {
        if (!::container.isInitialized) return false
        val lockState = container.lockState()
        lockState.refresh()
        if (lockState.locked.value) return false
        return runCatching {
            container.notificationRefreshScheduler().enqueue()
            true
        }.getOrDefault(false)
    }

    @VisibleForTesting
    fun installContainerForTesting(container: AppContainer) {
        this.container = container
    }
}
