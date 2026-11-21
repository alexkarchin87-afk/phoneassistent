package com.voiceagent.oneplus13.integrations.messenger

import android.content.Context
import com.squareup.moshi.JsonClass
import com.voiceagent.oneplus13.BuildConfig
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import com.voiceagent.oneplus13.core.awaitBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = false)
data class TgSendResult(val ok: Boolean)

/**
 * Thin client for the official Telegram Bot API. Used both to send messages the
 * agent composes and, via long-polling in TelegramBotService, to receive them.
 */
class TelegramClient(context: Context? = null, botToken: String? = null, private val apiBase: String = "https://api.telegram.org") {

    private val botToken: String = botToken
        ?: context?.let { SecretsStore(it) }?.getOrDefault(SecretsStore.KEY_TELEGRAM_BOT_TOKEN, BuildConfig.TELEGRAM_BOT_TOKEN)
        ?: BuildConfig.TELEGRAM_BOT_TOKEN

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS) // 30 seconds of polling plus network latency
        .build()

    private val base = "$apiBase/bot${this.botToken.trim()}"

    // A bot's offset must not be reused after the user selects another token.
    private val offsetKey = "offset_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(this.botToken.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val offsetPrefs = context?.applicationContext?.getSharedPreferences("telegram_polling", Context.MODE_PRIVATE)
    internal fun savedOffset(): Long = offsetPrefs?.getLong(offsetKey, 0L) ?: 0L
    internal fun saveOffset(offset: Long) {
        if (offsetPrefs != null && !offsetPrefs.edit().putLong(offsetKey, offset).commit()) {
            throw java.io.IOException("Cannot save Telegram polling position")
        }
    }

    suspend fun botUsername(): String {
        val request = Request.Builder().url("$base/getMe").get().build()
        val response = http.newCall(request).awaitBody()
        if (response.code !in 200..299) throw java.io.IOException("Telegram HTTP ${response.code}")
        val root = org.json.JSONObject(response.body)
        if (!root.optBoolean("ok")) throw java.io.IOException("Telegram API error")
        return root.optJSONObject("result")?.optString("username").orEmpty()
    }

    suspend fun sendMessage(chatId: String, text: String): Boolean {
        val json = org.json.JSONObject().put("chat_id", chatId).put("text", text).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/sendMessage").post(body).build()
        val response = http.newCall(request).awaitBody()
        return response.code in 200..299 && org.json.JSONObject(response.body).optBoolean("ok")
    }

    /** Long-polls getUpdates and returns any new messages as platform-agnostic Msg objects. */
    suspend fun pollUpdates(offset: Long): Pair<Long, List<Msg>> {
        val request = Request.Builder()
            .url("$base/getUpdates?timeout=30&offset=$offset")
            .get()
            .build()
        val resp = http.newCall(request).awaitBody()
        if (resp.code !in 200..299) throw java.io.IOException("Telegram polling HTTP ${resp.code}")
        return parseUpdates(resp.body, offset)
    }

    private fun parseUpdates(json: String, currentOffset: Long): Pair<Long, List<Msg>> {
        val root = org.json.JSONObject(json)
        if (!root.optBoolean("ok")) throw java.io.IOException("Telegram API error")
        val results = root.optJSONArray("result") ?: return currentOffset to emptyList()
        var maxUpdateId = currentOffset
        val msgs = mutableListOf<Msg>()
        for (i in 0 until results.length()) {
            val update = results.getJSONObject(i)
            maxUpdateId = maxOf(maxUpdateId, update.optLong("update_id", currentOffset) + 1)
            val message = update.optJSONObject("message") ?: continue
            val text = message.optString("text")
            if (text.isBlank()) continue
            val chat = message.optJSONObject("chat")
            val from = message.optJSONObject("from")
            msgs.add(
                Msg(
                    platform = MessengerPlatform.TELEGRAM,
                    chatId = chat?.optString("id").orEmpty(),
                    senderName = from?.optString("first_name").orEmpty(),
                    text = text,
                    isPrivateUserChat = chat != null && from != null && chat.optString("type") == "private" &&
                        chat.optString("id").isNotBlank() && chat.optString("id") == from.optString("id") &&
                        !from.optBoolean("is_bot"),
                    telegramUpdateId = update.getLong("update_id")
                )
            )
        }
        return maxUpdateId to msgs
    }

}

