package com.voiceagent.oneplus13.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class LocalModelManager(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("local_model", Context.MODE_PRIVATE)
    private val downloads = this.context.getSystemService(DownloadManager::class.java)
    private val directory get() = this.context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("LOCAL_STORAGE_UNAVAILABLE")
    private val partial get() = File(directory, "qwen-1.5b.part")
    val modelFile get() = File(directory, "qwen-1.5b.gguf")
    val isLocal get() = prefs.getBoolean("local_enabled", false)
    val downloadId get() = prefs.getLong("download_id", -1L)
    val ready get() = prefs.getBoolean("verified", false) && modelFile.isFile && modelFile.length() > 1_000_000_000L

    fun useCloud() { prefs.edit().putBoolean("local_enabled", false).apply() }
    fun useLocal() {
        check(ready) { "LOCAL_MODEL_MISSING" }
        prefs.edit().putBoolean("local_enabled", true).apply()
    }
    fun startDownload() {
        check(downloadId == -1L) { "LOCAL_DOWNLOAD_ALREADY_RUNNING" }
        check(directory.usableSpace > 1_500_000_000L) { "LOCAL_NO_SPACE" }
        partial.delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Qwen 2.5 1.5B для VoiceAgent")
            .setDescription("Локальная модель, около 1,12 ГБ")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, partial.name)
        prefs.edit().putLong("download_id", downloads.enqueue(request)).apply()
    }
    fun cancelDownload() {
        val id = downloadId
        if (id != -1L) downloads.remove(id)
        prefs.edit().remove("download_id").apply()
        partial.delete()
    }
    fun downloadStatus(): DownloadStatus {
        val id = downloadId
        if (id == -1L) return DownloadStatus.Idle
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return DownloadStatus.Failed
            return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Downloaded
                DownloadManager.STATUS_FAILED -> DownloadStatus.Failed
                else -> {
                    val got = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    DownloadStatus.Downloading(if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else null)
                }
            }
        }
        return DownloadStatus.Failed
    }
    suspend fun verifyDownload() = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == SHA256) { "LOCAL_CHECKSUM_FAILED" }
        check(partial.renameTo(modelFile)) { "LOCAL_RENAME_FAILED" }
        prefs.edit().putBoolean("verified", true).remove("download_id").apply()
    }
    companion object {
        const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/dd26da440ef0330c47919d1ecae0966d24022222/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    }
}

sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data object Downloaded : DownloadStatus
    data object Failed : DownloadStatus
    data class Downloading(val percent: Int?) : DownloadStatus
}
