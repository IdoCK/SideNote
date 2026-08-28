package com.sidenote.app

import android.content.Context

interface AppContainer

class ProductionAppContainer(
    private val context: Context,
) : AppContainer
