package com.addonserver

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages config.json with atomic read/write and hot-reload.
 *
 * Config structure:
 * {
 *   "provider_A": {
 *     "cookie": "cf_clearance=...",
 *     "user_agent": "Mozilla/5.0 ..."
 *   }
 * }
 *
 * Thread-safe: uses ReentrantReadWriteLock for concurrent access
 * and AtomicReference for the in-memory cache.
 */
object ConfigManager {

    private const val CONFIG_FILE = "config.json"
    private const val PREFS_NAME = "addon_server_prefs"
    private const val KEY_LAST_UPDATE = "last_config_update"

    private lateinit var appContext: Context
    private val gson = Gson()
    private val configLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val configRef: AtomicReference<Map<String, ProviderConfig>> = AtomicReference(emptyMap())

    // Observers for hot-reload notifications
    private val observers = mutableListOf<(Map<String, ProviderConfig>) -> Unit>()

    data class ProviderConfig(
        val cookie: String = "",
        val user_agent: String = ""
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        loadConfig()
    }

    private fun configFile(): File = File(appContext.filesDir, CONFIG_FILE)

    /**
     * Load config from disk into memory cache.
     * Called on init and after every write.
     */
    private fun loadConfig() {
        configLock.write {
            try {
                val file = configFile()
                if (!file.exists()) {
                    // Create default config with placeholder provider
                    val default = mapOf(
                        "provider_A" to ProviderConfig(
                            cookie = "cf_clearance=PLACEHOLDER",
                            user_agent = "Mozilla/5.0 (Linux; Android 11; SWTV-22AE-FHD) AppleWebKit/537.36"
                        )
                    )
                    saveToDisk(default)
                    configRef.set(default)
                } else {
                    val json = file.readText(Charsets.UTF_8)
                    val type = object : TypeToken<Map<String, ProviderConfig>>() {}.type
                    val config: Map<String, ProviderConfig> = gson.fromJson(json, type)
                        ?: emptyMap()
                    configRef.set(config)
                }
                // Notify observers
                observers.forEach { it(configRef.get()) }
            } catch (e: Exception) {
                // If config is corrupt, reset to default
                configRef.set(emptyMap())
            }
        }
    }

    private fun saveToDisk(config: Map<String, ProviderConfig>) {
        val file = configFile()
        val tmpFile = File(appContext.filesDir, "$CONFIG_FILE.tmp")

        // Atomic write: write to temp file, then rename
        FileOutputStream(tmpFile).use { fos ->
            fos.write(gson.toJson(config).toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync() // Force fsync for durability
        }

        if (file.exists()) file.delete()
        if (!tmpFile.renameTo(file)) {
            throw IOException("Failed to rename temp config file")
        }

        // Record update timestamp
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get current config (thread-safe read from memory cache).
     */
    fun getConfig(): Map<String, ProviderConfig> = configLock.read {
        configRef.get()
    }

    /**
     * Get config for a specific provider.
     */
    fun getProviderConfig(providerId: String): ProviderConfig? {
        return getConfig()[providerId]
    }

    /**
     * Update cookie for a provider - atomic write + hot-reload.
     */
    fun updateCookie(providerId: String, cookie: String): Boolean {
        configLock.write {
            try {
                val current = configRef.get().toMutableMap()
                val existing = current[providerId] ?: ProviderConfig()
                current[providerId] = existing.copy(cookie = cookie)
                saveToDisk(current)
                configRef.set(current)
                observers.forEach { it(current) }
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }

    /**
     * Update user-agent for a provider - atomic write + hot-reload.
     */
    fun updateUserAgent(providerId: String, userAgent: String): Boolean {
        configLock.write {
            try {
                val current = configRef.get().toMutableMap()
                val existing = current[providerId] ?: ProviderConfig()
                current[providerId] = existing.copy(user_agent = userAgent)
                saveToDisk(current)
                configRef.set(current)
                observers.forEach { it(current) }
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }

    /**
     * Add a new provider entry.
     */
    fun addProvider(providerId: String, config: ProviderConfig): Boolean {
        configLock.write {
            try {
                val current = configRef.get().toMutableMap()
                current[providerId] = config
                saveToDisk(current)
                configRef.set(current)
                observers.forEach { it(current) }
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }

    /**
     * Register observer for config changes (hot-reload).
     */
    fun observe(callback: (Map<String, ProviderConfig>) -> Unit) {
        observers.add(callback)
    }

    /**
     * Get list of all provider IDs.
     */
    fun getProviderIds(): List<String> = getConfig().keys.toList()

    /**
     * Get the config file path (for Python bridge).
     */
    fun getConfigFilePath(): String = configFile().absolutePath

    /**
     * Get last update timestamp.
     */
    fun getLastUpdateTime(): Long {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE, 0L)
    }
}
