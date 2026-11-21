package com.voiceagent.oneplus13.integrations.messenger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Client for Meta's official WhatsApp Business Cloud API. Deliberately does NOT use
 * any unofficial/scraping approach (those violate WhatsApp's Terms of Service and
 * risk the number being banned) — this requires a registered WhatsApp Business
 * phone number ID and a permanent access token from the Meta developer console.
 *
 * Incoming messages arrive via a webhook you configure in the Meta app dashboard,
 * not by polling from the phone; MessengerBridge only covers outbound sends here.
 */
class WhatsAppClient(
    private val phoneNumberId: String,
    private val accessToken: String,
    private val apiVersion: String = "v20.0"
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(toE164: String, text: String) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("messaging_product", "whatsapp").put("to", toE164)
            .put("type", "text").put("text", JSONObject().put("body", text)).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://graph.facebook.com/$apiVersion/$phoneNumberId/messages")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }

}
