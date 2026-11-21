package com.voiceagent.oneplus13.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for secrets added AT RUNTIME (API keys typed into
 * Settings, bot tokens captured from Telegram notifications) — as opposed to
 * BuildConfig fields, which only cover secrets baked in at compile time via
 * local.properties.
 *
 * Backed by Jetpack Security's EncryptedSharedPreferences (AES-256-GCM), so
 * values are encrypted at rest even on a rooted device without extra work.
 */
class SecretsStore(context: Context) {

    private val storage = storageFor(context.applicationContext)
    private val prefs = storage.prefs
    private val keyIndex = storage.keyIndex

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        // Only the KEY NAME (a label like "project_secret:my_bot:TELEGRAM_BOT_TOKEN") is
        // recorded here, in plain SharedPreferences, purely so Settings can list which
        // secrets exist without decrypting everything. The secret VALUE only ever lives
        // in the encrypted store above.
        if (key.startsWith("project_secret:")) {
            keyIndex.edit().putBoolean(key, true).apply()
        }
    }

    fun get(key: String): String? = prefs.getString(key, null)

    /** Имена секретов сгенерированных проектов (только имена, не значения — см. put()). */
    fun projectSecretKeys(): List<String> = keyIndex.all.keys.toList()

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
        if (key.startsWith("project_secret:")) keyIndex.edit().remove(key).apply()
    }

    /** Convenience: value from SecretsStore if the user has set one, else [fallback]
     *  (typically a BuildConfig field from local.properties). Lets runtime-entered
     *  keys in Settings override compile-time ones without a rebuild. */
    fun getOrDefault(key: String, fallback: String): String = get(key)?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        private data class Storage(val prefs: SharedPreferences, val keyIndex: SharedPreferences)
        @Volatile private var sharedStorage: Storage? = null

        private fun storageFor(context: Context): Storage = sharedStorage ?: synchronized(this) {
            sharedStorage ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                Storage(
                    EncryptedSharedPreferences.create(
                        context, "voice_agent_secrets", masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    ),
                    context.getSharedPreferences("voice_agent_secret_index", Context.MODE_PRIVATE)
                ).also { sharedStorage = it }
            }
        }
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val KEY_CLAUDE_API_KEY = "claude_api_key"
        const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        const val KEY_TELEGRAM_OWNER_ID = "telegram_owner_id"
        const val KEY_MAX_SERVICE_TOKEN = "max_service_token"

        /** Prefix for tokens captured automatically from BotFather notifications,
         *  one per generated project: pending_botfather_token:<project_name> */
        fun pendingBotFatherTokenKey(projectName: String) = "pending_botfather_token:$projectName"
    }
}
