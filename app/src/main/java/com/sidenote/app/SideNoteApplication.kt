package com.sidenote.app

import android.app.Application
import androidx.annotation.VisibleForTesting

class SideNoteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainer(this)
    }

    @VisibleForTesting
    fun installContainerForTesting(container: AppContainer) {
        this.container = container
    }
}
