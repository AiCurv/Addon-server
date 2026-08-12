package com.addonserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (isRunning) return START_STICKY

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        acquireWakeLocks()
        startPythonServer()
        TelegramBotEngine.start(this)
        keepWakeLockAlive()

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

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Addon Server Active")
                .setContentText("Stremio server on port $SERVER_PORT | Telegram bot online")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Addon Server Active")
                .setContentText("Stremio server on port $SERVER_PORT")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    private fun acquireWakeLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AddonServer::StremioWakeLock"
        ).apply {
            acquire(12 * 60 * 60 * 1000L)
        }

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
            val wl = wakeLock
            if (wl != null && wl.isHeld) {
                wl.release()
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val wfl = wifiLock
            if (wfl != null && wfl.isHeld) {
                wfl.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startPythonServer() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val py = Python.getInstance()
                val module = py.getModule("addon_server")
                val configPath = ConfigManager.getConfigFilePath()
                module.callAttr("start_server", configPath, SERVER_PORT)
            } catch (e: PyException) {
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
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun keepWakeLockAlive() {
        lifecycleScope.launch(Dispatchers.Default) {
            while (isRunning) {
                delay(30 * 60 * 1000L)
                try {
                    val wl = wakeLock
                    if (wl != null && !wl.isHeld) {
                        wl.acquire(12 * 60 * 60 * 1000L)
                    }
                    val wfl = wifiLock
                    if (wfl != null && !wfl.isHeld) {
                        wfl.acquire()
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
