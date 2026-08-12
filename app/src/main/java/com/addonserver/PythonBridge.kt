package com.addonserver

import com.chaquo.python.Python

/**
 * Bridge between Kotlin and embedded Python via Chaquopy.
 * Provides methods for the Python server to call back into Kotlin
 * for config reading (hot-reload: Python asks Kotlin for current config
 * on every request, not cached in Python).
 */
object PythonBridge {

    /**
     * Called by Python to get the current config JSON string.
     * This is the hot-reload mechanism: every HTTP request in Python
     * calls this to get the latest cookie/user_agent from config.json
     * without restarting the Python server.
     */
    @JvmStatic
    fun getConfigJson(): String {
        val config = ConfigManager.getConfig()
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in config) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"$key\":{\"cookie\":${escapeJson(value.cookie)},\"user_agent\":${escapeJson(value.user_agent)}}")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * Called by Python to get a specific provider's cookie.
     */
    @JvmStatic
    fun getCookie(providerId: String): String {
        return ConfigManager.getProviderConfig(providerId)?.cookie ?: ""
    }

    /**
     * Called by Python to get a specific provider's user-agent.
     */
    @JvmStatic
    fun getUserAgent(providerId: String): String {
        return ConfigManager.getProviderConfig(providerId)?.user_agent ?: ""
    }

    /**
     * Called by Python to get all provider IDs.
     */
    @JvmStatic
    fun getProviderIds(): String {
        return ConfigManager.getProviderIds().joinToString(",")
    }

    private fun escapeJson(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
