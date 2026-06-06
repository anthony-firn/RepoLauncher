package com.example.repolauncher

import android.app.Application

class RepoLauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: RepoLauncherApp
            private set
    }
}
