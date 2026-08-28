package com.sidenote.app

import android.app.Application

class SideNoteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainer(this)
    }
}
