package com.addonserver

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Application class - initializes Chaquopy Python runtime
 * and provides app-wide singletons for ConfigManager.
 */
class AddonServerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Python runtime (Chaquopy)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Initialize ConfigManager singleton
        ConfigManager.init(this)
    }
}
