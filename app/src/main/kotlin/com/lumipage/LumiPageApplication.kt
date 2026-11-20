package com.lumipage

import android.app.Application
import com.lumipage.di.AppContainer

class LumiPageApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
