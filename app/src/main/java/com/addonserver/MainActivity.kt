package com.addonserver

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/**
 * Main Activity for Android TV - displays server status, local IP,
 * and Telegram bot connection status. Designed for 720p/1080p TV viewport
 * with D-pad navigation support.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serverDot: View
    private lateinit var serverStatusLabel: TextView
    private lateinit var localIpText: TextView
    private lateinit var stremioUrlText: TextView
    private lateinit var botDot: View
    private lateinit var botStatusLabel: TextView
    private lateinit var botUserText: TextView
    private lateinit var providersText: TextView

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        serverDot = findViewById(R.id.serverDot)
        serverStatusLabel = findViewById(R.id.serverStatusLabel)
        localIpText = findViewById(R.id.localIpText)
        stremioUrlText = findViewById(R.id.stremioUrlText)
        botDot = findViewById(R.id.botDot)
        botStatusLabel = findViewById(R.id.botStatusLabel)
        botUserText = findViewById(R.id.botUserText)
        providersText = findViewById(R.id.providersText)

        // Start the foreground service
        startStremioService()

        // Start UI refresh loop
        startStatusRefresh()
    }

    private fun startStremioService() {
        val intent = Intent(this, StremioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceRunning = true
    }

    private fun startStatusRefresh() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                refreshStatus()
                delay(2000) // Refresh every 2 seconds
            }
        }
    }

    private fun refreshStatus() {
        val localIp = getLocalIpAddress()

        // Server status
        val isServerRunning = StremioService.isRunning
        serverDot.background = if (isServerRunning) {
            getDrawable(R.drawable.dot_green)
        } else {
            getDrawable(R.drawable.dot_red)
        }
        serverStatusLabel.text = if (isServerRunning) "Server: Running" else "Server: Stopped"
        localIpText.text = "IP: $localIp"
        stremioUrlText.text = "http://$localIp:7000/manifest.json"

        // Bot status
        val isBotConnected = TelegramBotEngine.isConnected
        botDot.background = if (isBotConnected) {
            getDrawable(R.drawable.dot_green)
        } else {
            getDrawable(R.drawable.dot_yellow)
        }
        botStatusLabel.text = if (isBotConnected) {
            "Telegram Bot: Connected"
        } else {
            "Telegram Bot: Polling..."
        }
        botUserText.text = "Admin ID: ${TelegramBotEngine.ADMIN_USER_ID}"

        // Provider config
        val providers = ConfigManager.getProviderIds()
        providersText.text = if (providers.isNotEmpty()) {
            providers.joinToString(", ")
        } else {
            "No providers configured"
        }
    }

    /**
     * Get the device's local IP address (WiFi or Ethernet).
     */
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            for (intf in interfaces) {
                // Skip loopback and down interfaces
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.interfaceAddresses ?: continue
                for (addr in addrs) {
                    val inetAddr = addr.address ?: continue
                    if (inetAddr.isLoopbackAddress) continue
                    val host = inetAddr.hostAddress ?: continue
                    // Return first non-loopback IPv4 address
                    if (!host.contains(":")) return host
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return "0.0.0.0"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service continues running in background (it's a foreground service)
    }
}
