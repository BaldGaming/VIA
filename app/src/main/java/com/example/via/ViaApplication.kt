package com.example.via

import android.app.Application
import timber.log.Timber

class ViaApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Plant the standard Debug tree
        Timber.plant(Timber.DebugTree())

        // Plant the custom tree
        Timber.plant(FileLoggingTree(applicationContext))

        // We get the default handler
        var defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        // We set a custom handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->

        }
    }
}