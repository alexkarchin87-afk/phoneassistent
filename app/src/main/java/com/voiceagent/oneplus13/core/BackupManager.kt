package com.voiceagent.oneplus13.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.voiceagent.oneplus13.ui.FormFactorOverride
import com.voiceagent.oneplus13.ui.UiPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Explicit JSON export of user memory and UI preferences. API keys and bot
 * credentials are never exported or silently restored from legacy files.
 */
class BackupManager(private val context: Context) {

    fun exportBackup(): File {
        val root = JSONObject()
        root.put("format_version", 2)
        root.put("exported_at", System.currentTimeMillis())

        // Never export API keys or generated project secrets in plaintext.

        // v2: факты с приоритетом (было — просто массив строк в "memory_facts";
        // тот старый формат по-прежнему читается при импорте, см. ниже).
        val factsJson = JSONArray()
        UserMemory(context).allDetailed().forEach { fact ->
            factsJson.put(
                JSONObject()
                    .put("text", fact.text)
                    .put("tier", fact.tier.name)
                    .put("ts", fact.timestampMillis)
            )
        }
        root.put("memory_facts_v2", factsJson)

        val prefs = UiPreferences(context)
        val uiJson = JSONObject()
        uiJson.put("form_factor_override", prefs.formFactorOverride.name)
        uiJson.put("overlay_enabled", prefs.overlayEnabled)
        uiJson.put("overlay_always_visible", prefs.overlayAlwaysVisible)
        root.put("ui_preferences", uiJson)

        val file = File(context.cacheDir, "voiceagent_backup.json")
        file.writeText(root.toString(2))
        return file
    }

    /** Возвращает false, если файл не похож на бэкап VoiceAgent (битый JSON и
     *  т.п.) — тогда ничего не применяется, настройки остаются как были. */
    fun importBackup(file: File): Boolean = runCatching {
        val root = JSONObject(file.readText())

        // Legacy backups may contain plaintext secrets. Never silently overwrite
        // the current bot owner or API credentials with untrusted file content.

        val memory = UserMemory(context)
        val factsV2 = root.optJSONArray("memory_facts_v2")
        if (factsV2 != null) {
            for (i in 0 until factsV2.length()) {
                val obj = factsV2.getJSONObject(i)
                val tier = runCatching { MemoryTier.valueOf(obj.optString("tier")) }.getOrDefault(MemoryTier.P2)
                memory.remember(obj.getString("text"), tier)
            }
        } else {
            // Бэкап со старого устройства (format_version 1) — факты без приоритета.
            root.optJSONArray("memory_facts")?.let { arr ->
                for (i in 0 until arr.length()) memory.remember(arr.getString(i))
            }
        }

        root.optJSONObject("ui_preferences")?.let { obj ->
            val prefs = UiPreferences(context)
            obj.optString("form_factor_override").takeIf { it.isNotBlank() }?.let { name ->
                runCatching { prefs.formFactorOverride = FormFactorOverride.valueOf(name) }
            }
            prefs.overlayEnabled = obj.optBoolean("overlay_enabled", prefs.overlayEnabled)
            prefs.overlayAlwaysVisible = obj.optBoolean("overlay_always_visible", prefs.overlayAlwaysVisible)
        }

        // Поле "llm_provider" могло быть в старых бэкапах (format_version 1) —
        // выбора модели больше нет, поле просто игнорируется при импорте.
        true
    }.getOrDefault(false)

    fun shareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

