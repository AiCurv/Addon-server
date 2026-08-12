package com.addonserver

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Telegram Bot Engine - Runs long polling in a background coroutine.
 *
 * Features:
 * - Long-polling getUpdates with 30s timeout
 * - Admin-only filtering (ADMIN_USER_ID)
 * - Interactive InlineKeyboardMarkup menu system:
 *   [🔄 Status] [🍪 Update Cookie] [📋 List Addons]
 * - Callback query handling for button interactions
 * - Atomic config.json updates with instant hot-reload
 */
object TelegramBotEngine {

    private const val TAG = "TelegramBot"

    // Bot configuration
    const val BOT_TOKEN = "8976316906:AAEJEX1EXozJgg-lVVVZkfxnBKGez8N9jwo"
    const val ADMIN_USER_ID = 6404893345L

    private const val API_BASE = "https://api.telegram.org/bot$BOT_TOKEN"
    private const val POLL_TIMEOUT = 30 // seconds

    @Volatile
    var isConnected = false
        private set

    private var pollingJob: Job? = null
    private var scope: CoroutineScope? = null
    private var lastUpdateId = 0L
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Track users awaiting cookie/useragent input
    private val awaitingCookieFor = mutableMapOf<Long, String>()  // chatId -> providerId
    private val awaitingUserAgentFor = mutableMapOf<Long, String>()

    fun start(context: Context) {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        isConnected = true
        startPolling()
    }

    fun stop() {
        pollingJob?.cancel()
        scope?.cancel()
        isConnected = false
    }

    private fun startPolling() {
        pollingJob = scope?.launch {
            // Send startup message to admin
            sendMainMenu(ADMIN_USER_ID)

            while (isActive) {
                try {
                    pollUpdates()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}", e)
                    delay(5000) // Back off on error
                }
            }
        }
    }

    /**
     * Long-poll Telegram getUpdates endpoint.
     */
    private suspend fun pollUpdates() {
        val url = "$API_BASE/getUpdates?offset=${lastUpdateId + 1}&timeout=$POLL_TIMEOUT"
        val request = Request.Builder().url(url).get().build()

        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val body = response.body?.string() ?: return
            response.close()

            val json = JsonParser.parseString(body).asJsonObject
            if (!json.get("ok")?.asBoolean == true) return

            val updates = json.getAsJsonArray("result") ?: return
            for (update in updates) {
                val updateObj = update.asJsonObject
                val updateId = updateObj.get("update_id")?.asLong ?: continue
                lastUpdateId = updateId

                // Handle callback_query (inline button press)
                if (updateObj.has("callback_query")) {
                    handleCallbackQuery(updateObj.getAsJsonObject("callback_query"))
                    continue
                }

                // Handle message
                if (updateObj.has("message")) {
                    handleMessage(updateObj.getAsJsonObject("message"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getUpdates failed: ${e.message}")
        }
    }

    /**
     * Handle incoming text messages from admin.
     */
    private suspend fun handleMessage(message: JsonObject) {
        val chatId = message.get("chat")?.asJsonObject?.get("id")?.asLong ?: return
        val fromId = message.get("from")?.asJsonObject?.get("id")?.asLong ?: return

        // Only process messages from admin
        if (fromId != ADMIN_USER_ID) return

        val text = message.get("text")?.asString ?: ""

        when {
            text == "/start" || text == "/menu" -> {
                sendMainMenu(chatId)
            }
            text == "/status" -> {
                sendStatusMessage(chatId)
            }
            // Check if user is in cookie-input mode
            awaitingCookieFor.containsKey(chatId) -> {
                val providerId = awaitingCookieFor.remove(chatId)!!
                handleCookieInput(chatId, providerId, text)
            }
            // Check if user is in user-agent input mode
            awaitingUserAgentFor.containsKey(chatId) -> {
                val providerId = awaitingUserAgentFor.remove(chatId)!!
                handleUserAgentInput(chatId, providerId, text)
            }
        }
    }

    /**
     * Handle callback_query from inline keyboard buttons.
     */
    private suspend fun handleCallbackQuery(callback: JsonObject) {
        val fromId = callback.get("from")?.asJsonObject?.get("id")?.asLong ?: return
        if (fromId != ADMIN_USER_ID) return

        val chatId = callback.get("message")?.asJsonObject?.get("chat")?.asJsonObject?.get("id")?.asLong ?: return
        val messageId = callback.get("message")?.asJsonObject?.get("message_id")?.asLong ?: return
        val data = callback.get("data")?.asString ?: return

        // Answer the callback query to dismiss the loading indicator
        answerCallbackQuery(callback.get("id")?.asString ?: "")

        when {
            data == "action_status" -> {
                val statusText = buildStatusText()
                editMessageText(chatId, messageId, statusText)
            }
            data == "action_update_cookie" -> {
                val providers = ConfigManager.getProviderIds()
                val keyboard = buildProviderKeyboard("cookie")
                val text = if (providers.isNotEmpty()) {
                    "🍪 *Select provider to update cookie:*\n\n" +
                    providers.mapIndexed { i, p -> "${i + 1}. `$p`" }.joinToString("\n") +
                    "\n\nOr send: `/cookie provider_id value`"
                } else {
                    "No providers configured yet."
                }
                editMessageText(chatId, messageId, text, keyboard)
            }
            data == "action_update_ua" -> {
                val providers = ConfigManager.getProviderIds()
                val keyboard = buildProviderKeyboard("ua")
                val text = if (providers.isNotEmpty()) {
                    "🌐 *Select provider to update User\\-Agent:*\n\n" +
                    providers.mapIndexed { i, p -> "${i + 1}. `$p`" }.joinToString("\n")
                } else {
                    "No providers configured."
                }
                editMessageText(chatId, messageId, text, keyboard)
            }
            data == "action_list_addons" -> {
                val listText = buildAddonsListText()
                editMessageText(chatId, messageId, listText)
            }
            data.startsWith("cookie:") -> {
                val providerId = data.substringAfter("cookie:")
                awaitingCookieFor[chatId] = providerId
                editMessageText(
                    chatId, messageId,
                    "🍪 *Update Cookie for `$providerId`*\n\n" +
                    "Please reply with the new cookie value:\n" +
                    "Example: `cf_clearance=abc123; other=vals`"
                )
            }
            data.startsWith("ua:") -> {
                val providerId = data.substringAfter("ua:")
                awaitingUserAgentFor[chatId] = providerId
                editMessageText(
                    chatId, messageId,
                    "🌐 *Update User\\-Agent for `$providerId`*\n\n" +
                    "Please reply with the new User\\-Agent string."
                )
            }
        }
    }

    /**
     * Process cookie input from admin.
     */
    private suspend fun handleCookieInput(chatId: Long, providerId: String, cookieValue: String) {
        val success = ConfigManager.updateCookie(providerId, cookieValue)
        if (success) {
            sendMessage(
                chatId,
                "✅ *Success\\!*\n" +
                "Cookie for `$providerId` updated and applied *instantly*\\.\n" +
                "No app restart needed — next Stremio request will use the new cookie\\."
            )
        } else {
            sendMessage(chatId, "❌ Failed to update cookie\\. Check logs\\.")
        }
        sendMainMenu(chatId)
    }

    /**
     * Process user-agent input from admin.
     */
    private suspend fun handleUserAgentInput(chatId: Long, providerId: String, uaValue: String) {
        val success = ConfigManager.updateUserAgent(providerId, uaValue)
        if (success) {
            sendMessage(
                chatId,
                "✅ *Success\\!*\n" +
                "User\\-Agent for `$providerId` updated and applied *instantly*\\."
            )
        } else {
            sendMessage(chatId, "❌ Failed to update user\\-agent\\. Check logs\\.")
        }
        sendMainMenu(chatId)
    }

    // ============================================================
    //  Telegram API Methods
    // ============================================================

    /**
     * Send the main menu with inline keyboard buttons.
     */
    private suspend fun sendMainMenu(chatId: Long) {
        val text = "🎛 *Addon Server Control Panel*\n\nServer running on port ${StremioService.SERVER_PORT}"
        val keyboard = buildMainKeyboard()
        sendMessage(chatId, text, keyboard)
    }

    private fun buildMainKeyboard(): String {
        return gson.toJson(mapOf(
            "inline_keyboard" to listOf(
                listOf(
                    mapOf("text" to "🔄 Status", "callback_data" to "action_status"),
                    mapOf("text" to "🍪 Update Cookie", "callback_data" to "action_update_cookie")
                ),
                listOf(
                    mapOf("text" to "🌐 Update UA", "callback_data" to "action_update_ua"),
                    mapOf("text" to "📋 List Addons", "callback_data" to "action_list_addons")
                )
            )
        ))
    }

    private fun buildProviderKeyboard(prefix: String): String {
        val providers = ConfigManager.getProviderIds()
        val rows = providers.chunked(2).map { row ->
            row.map { p ->
                mapOf("text" to p, "callback_data" to "$prefix:$p")
            }
        }
        val backRow = listOf(mapOf("text" to "🔙 Back", "callback_data" to "action_status"))
        return gson.toJson(mapOf("inline_keyboard" to (rows + listOf(backRow))))
    }

    private fun buildStatusText(): String {
        val providers = ConfigManager.getProviderIds()
        val sb = StringBuilder()
        sb.append("🔄 *Addon Server Status*\n\n")
        sb.append("• Server: ${if (StremioService.isRunning) "✅ Running" else "❌ Stopped"}\n")
        sb.append("• Port: ${StremioService.SERVER_PORT}\n")
        sb.append("• Bot: ${if (isConnected) "✅ Connected" else "⏳ Polling"}\n")
        sb.append("• Providers: ${providers.size}\n\n")

        for (pid in providers) {
            val cfg = ConfigManager.getProviderConfig(pid)
            val cookieHealth = if (cfg?.cookie?.isNotEmpty() == true && cfg.cookie != "cf_clearance=PLACEHOLDER") {
                "✅"
            } else {
                "⚠️"
            }
            sb.append("  $cookieHealth `$pid`\n")
        }
        return sb.toString()
    }

    private fun buildAddonsListText(): String {
        val providers = ConfigManager.getProviderIds()
        val sb = StringBuilder()
        sb.append("📋 *Managed Addons*\n\n")
        if (providers.isEmpty()) {
            sb.append("No providers configured\\.\n")
        } else {
            for (pid in providers) {
                val cfg = ConfigManager.getProviderConfig(pid)
                sb.append("▸ *$pid*\n")
                val cookiePreview = (cfg?.cookie?.take(30) ?: "none")
                val uaPreview = (cfg?.user_agent?.take(40) ?: "none")
                sb.append("  Cookie: `$cookiePreview...`\n")
                sb.append("  UA: `$uaPreview...`\n\n")
            }
        }
        return sb.toString()
    }

    private suspend fun sendStatusMessage(chatId: Long) {
        val text = buildStatusText()
        sendMessage(chatId, text)
    }

    /**
     * Send a message via Telegram API.
     */
    private suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyMarkup: String? = null
    ) {
        val payload = mutableMapOf(
            "chat_id" to chatId,
            "text" to text,
            "parse_mode" to "MarkdownV2"
        )
        if (replyMarkup != null) {
            payload["reply_markup"] = replyMarkup
        }

        val jsonBody = gson.toJson(payload)
        val request = Request.Builder()
            .url("$API_BASE/sendMessage")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    /**
     * Edit an existing message (for inline button responses).
     */
    private suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: String? = null
    ) {
        val payload = mutableMapOf(
            "chat_id" to chatId,
            "message_id" to messageId,
            "text" to text,
            "parse_mode" to "MarkdownV2"
        )
        if (replyMarkup != null) {
            payload["reply_markup"] = replyMarkup
        }

        val jsonBody = gson.toJson(payload)
        val request = Request.Builder()
            .url("$API_BASE/editMessageText")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "editMessageText failed: ${e.message}")
        }
    }

    /**
     * Answer a callback query (dismisses loading spinner on button).
     */
    private suspend fun answerCallbackQuery(callbackQueryId: String) {
        val payload = mapOf("callback_query_id" to callbackQueryId)
        val jsonBody = gson.toJson(payload)
        val request = Request.Builder()
            .url("$API_BASE/answerCallbackQuery")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "answerCallbackQuery failed: ${e.message}")
        }
    }
}
