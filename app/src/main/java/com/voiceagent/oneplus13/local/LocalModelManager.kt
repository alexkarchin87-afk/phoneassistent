package com.voiceagent.oneplus13.local

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
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
    private val smallModelFile get() = File(directory, "qwen-1.5b.gguf")
    private val recommendedPartial get() = File(directory, "qwen-3.5-4b.part")
    private val recommendedModelFile get() = File(directory, "qwen-3.5-4b.gguf")
    private val recommendedSelected get() = prefs.getBoolean("recommended_selected", false)
    val modelFile get() = if (recommendedSelected) recommendedModelFile else smallModelFile
    val contextForInference: Context get() = context
    val selectedUri: Uri? get() = prefs.getString("selected_uri", null)?.let(Uri::parse)
    val isLocal get() = prefs.getBoolean("local_enabled", false)
    val downloadId get() = prefs.getLong("download_id", -1L)
    val downloadedReady get() = prefs.getBoolean("verified", false) && smallModelFile.isFile && smallModelFile.length() == 1_117_320_736L && smallModelFile.lastModified() == prefs.getLong("verified_modified", -1L)
    val recommendedReady get() = prefs.getBoolean("recommended_verified", false) && recommendedModelFile.isFile && recommendedModelFile.length() == 3_032_091_584L && recommendedModelFile.lastModified() == prefs.getLong("recommended_modified", -1L)
    val recommendedDownloadId get() = prefs.getLong("recommended_download_id", -1L)
    val ready: Boolean get() = selectedUri?.let { uri -> context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } } ?: if (recommendedSelected) recommendedReady else downloadedReady
    val selectedName: String get() = selectedUri?.let { selected -> importedModels().firstOrNull { it.uri == selected }?.name ?: "выбранный файл" } ?: if (recommendedSelected) "Qwen 3.5 4B" else "Qwen 2.5 1.5B"
    fun importedModels(): List<ImportedModel> = runCatching {
        val data = JSONArray(prefs.getString("models", "[]"))
        (0 until data.length()).map { data.getJSONObject(it) }.map { ImportedModel(it.getString("name"), Uri.parse(it.getString("uri"))) }
    }.getOrDefault(emptyList())
    suspend fun importModel(uri: Uri): ImportedModel = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            val magic = ByteArray(4)
            val read = Os.read(fd.fileDescriptor, magic, 0, 4)
            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
            check(read == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "LOCAL_NOT_GGUF" }
        } ?: error("LOCAL_FILE_UNAVAILABLE")
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Модель GGUF"
        check(!Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(name)) { "LOCAL_SHARDED_MODEL" }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val model = ImportedModel(name.take(180), uri)
        val models = (importedModels().filterNot { it.uri == uri } + model).takeLast(10)
        val json = JSONArray()
        models.forEach { json.put(JSONObject().put("name", it.name).put("uri", it.uri.toString())) }
        prefs.edit().putString("models", json.toString()).apply()
        model
    }
    fun selectImported(model: ImportedModel) {
        check(importedModels().any { it.uri == model.uri })
        prefs.edit().putString("selected_uri", model.uri.toString()).putBoolean("recommended_selected", false).putBoolean("local_enabled", true).apply()
    }
    fun selectDownloaded() {
        check(downloadedReady) { "LOCAL_MODEL_MISSING" }
        prefs.edit().remove("selected_uri").putBoolean("recommended_selected", false).putBoolean("local_enabled", true).apply()
    }

    fun selectRecommended() {
        check(recommendedReady) { "LOCAL_MODEL_MISSING" }
        prefs.edit().remove("selected_uri").putBoolean("recommended_selected", true).putBoolean("local_enabled", true).apply()
    }
    fun startRecommendedDownload() {
        check(recommendedDownloadId == -1L) { "LOCAL_DOWNLOAD_ALREADY_RUNNING" }
        check(directory.usableSpace > 3_500_000_000L) { "LOCAL_NO_SPACE" }
        recommendedPartial.delete()
        val request = DownloadManager.Request(Uri.parse(RECOMMENDED_URL))
            .setTitle("Qwen 3.5 4B для VoiceAgent")
            .setDescription("Локальная модель, около 3,03 ГБ")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(false).setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, recommendedPartial.name)
        prefs.edit().putLong("recommended_download_id", downloads.enqueue(request)).apply()
    }
    fun cancelRecommendedDownload() {
        if (recommendedDownloadId != -1L) downloads.remove(recommendedDownloadId)
        prefs.edit().remove("recommended_download_id").apply()
        recommendedPartial.delete()
    }
    fun recommendedDownloadStatus(): DownloadStatus = statusFor(recommendedDownloadId)
    suspend fun verifyRecommendedDownload() = withContext(Dispatchers.IO) {
        verifyFile(recommendedPartial, RECOMMENDED_SHA256)
        check(recommendedPartial.length() == 3_032_091_584L) { "LOCAL_CHECKSUM_FAILED" }
        check(recommendedPartial.renameTo(recommendedModelFile)) { "LOCAL_RENAME_FAILED" }
        prefs.edit().putBoolean("recommended_verified", true).putLong("recommended_modified", recommendedModelFile.lastModified()).remove("recommended_download_id").apply()
    }

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
    fun downloadStatus(): DownloadStatus = statusFor(downloadId)
    private fun statusFor(id: Long): DownloadStatus {
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
        verifyFile(partial, SHA256)
        check(partial.renameTo(smallModelFile)) { "LOCAL_RENAME_FAILED" }
        prefs.edit().putBoolean("verified", true).putLong("verified_modified", smallModelFile.lastModified()).remove("download_id").apply()
    }
    private suspend fun verifyFile(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == expected) { "LOCAL_CHECKSUM_FAILED" }
    }
    companion object {
        const val RECOMMENDED_URL = "https://huggingface.co/ggml-org/Qwen3.5-4B-GGUF/resolve/8d43d974efc48399cb58957ad49c8b26bf5a109c/Qwen3.5-4B-Q4_K_M.gguf"
        const val RECOMMENDED_SHA256 = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
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

data class ImportedModel(val name: String, val uri: Uri)
