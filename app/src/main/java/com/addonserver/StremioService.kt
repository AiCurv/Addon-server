package com.addonserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service that:
 * 1. Acquires PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF to prevent TV throttling
 * 2. Starts the embedded Python HTTP server on port 7000
 * 3. Starts the Telegram Bot polling engine
 * 4. Runs persistently with a low-priority notification (safe for Android TV Leanback)
 */
class StremioService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "addon_server_channel"
        const val CHANNEL_NAME = "Addon Server"
        const val NOTIFICATION_ID = 1001
        const val SERVER_PORT = 7000

        @Volatile
        var isRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pythonServerRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (isRunning) return START_STICKY

        // Start as foreground service immediately (Android 8+ requirement)
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Acquire wake locks
        acquireWakeLocks()

        // Start Python HTTP server
        startPythonServer()

        // Start Telegram Bot
        TelegramBotEngine.start(this)

        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPythonServer()
        TelegramBotEngine.stop()
        releaseWakeLocks()
        isRunning = false
    }

    /**
     * Create low-priority notification channel for Android TV Leanback.
     * Uses IMPORTANCE_LOW so it doesn't pop up as a heads-up notification
     * which would interfere with TV viewing.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Stremio addon server is running"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Addon Server Active")
                .setContentText("Stremio server on port $SERVER_PORT | Telegram bot online")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Addon Server Active")
                .setContentText("Stremio server on port $SERVER_PORT")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    /**
     * Acquire PARTIAL_WAKE_LOCK to keep CPU running when screen off,
     * and WIFI_MODE_FULL_HIGH_PERF to maintain low-latency WiFi
     * (prevents TV from throttling network when display dims).
     */
    private fun acquireWakeLocks() {
        // Partial wake lock - keeps CPU alive
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AddonServer::StremioWakeLock"
        ).apply {
            acquire(12 * 60 * 60 * 1000L) // 12 hours max, re-acquire periodically
        }

        // High-performance WiFi lock - prevents WiFi throttling
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "AddonServer::StremioWifiLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) { }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) { }
    }

    /**
     * Start the embedded Python HTTP server via Chaquopy.
     * The Python script runs addon_server.py which handles:
     * - /manifest.json (Stremio addon manifest)
     * - /stream/* (video stream proxy with dynamic Cloudflare headers)
     * - /catalog/* (content catalogs)
     */
    private fun startPythonServer() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("addon_server")

                // Pass config file path and port to Python
                val configPath = ConfigManager.getConfigFilePath()
                module.callAttr("start_server", configPath, SERVER_PORT)

                pythonServerRunning = true
            } catch (e: PyException) {
                // Python error - log but don't crash the service
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopPythonServer() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("addon_server")
                module.callAttr("stop_server")
                pythonServerRunning = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Periodically re-acquire wake lock (Android may release long-held locks).
     */
    private fun keepWakeLockAlive() {
        lifecycleScope.launch(Dispatchers.Default) {
            while (isRunning) {
                kotlinx.coroutines.delay(30 * 60 * 1000L) // 30 minutes
                try {
                    wakeLock?.let {
                        if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L)
                    }
                    wifiLock?.let {
                        if (!it.isHeld) it.acquire()
                    }
                } catch (e: Exception) { }
            }
        }
    }
}
