package com.voiceagent.oneplus13.devassist

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a generated project to disk as plain files (app-internal, always writable,
 * no permissions needed) and also as a single .zip copied into the public Downloads
 * collection, so it's easy to find in a Files app or move into Termux
 * (`~/storage/downloads/` after `termux-setup-storage`).
 */
class ProjectStorage(private val context: Context) {

    fun save(project: GeneratedProject): File {
        val safeName = sanitizeProjectName(project.projectName)
        val projectDir = File(context.filesDir, "generated_projects/$safeName").apply { mkdirs() }
        // Единственная техническая граница между тем, что пишет LLM, и остальной
        // файловой системой: даже если модель ошибётся или промпт когда-нибудь
        // обманут, сгенерированный файл физически не может выйти за пределы
        // своей же папки проекта — а значит не может дотянуться до кода самого
        // VoiceAgent или чужих данных на телефоне (см. isPathSafe).
        val safeFiles = project.files.filter { isPathSafe(projectDir, it.relativePath) }
        safeFiles.forEach { file ->
            val target = File(projectDir, file.relativePath)
            target.parentFile?.mkdirs()
            target.writeText(file.content)
        }
        ensureGitignore(projectDir, safeFiles)
        File(projectDir, PLATFORM_META_FILE).writeText(project.platform)
        val zip = zipProject(projectDir, safeName)
        copyToDownloads(zip, "$safeName.zip")
        return zip
    }

    /** Отклоняет абсолютные пути и любые "../" — не даёт файлу из сгенерированного
     *  проекта записаться выше собственной папки проекта. */
    private fun isPathSafe(projectDir: File, relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val root = projectDir.canonicalFile
        val candidate = File(projectDir, relativePath).canonicalFile
        return candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
    }

    /** Файлы уже сохранённого проекта — для "продолжить проект" (CodeGenAgent.revise),
     *  без .gitignore, без служебной метки платформы и без соседнего .zip (он лежит
     *  рядом с папкой, не внутри неё). */
    fun loadProjectFiles(projectName: String): List<ProjectFile>? {
        val projectDir = projectDirectory(projectName) ?: return null
        if (!projectDir.isDirectory) return null
        return projectDir.walkTopDown()
            .filter { it.isFile && it.name != PLATFORM_META_FILE &&
                it.canonicalPath.startsWith(projectDir.canonicalPath + File.separator) }
            .map { file -> ProjectFile(file.relativeTo(projectDir).path, file.readText()) }
            .toList()
    }

    /** "android" или "windows" — что было зафиксировано при первой генерации
     *  проекта; "android", если метка почему-то потерялась (старые проекты,
     *  сохранённые до этого поля). */
    fun loadProjectPlatform(projectName: String): String {
        val projectDir = projectDirectory(projectName) ?: return "android"
        val file = File(projectDir, PLATFORM_META_FILE)
        if (!file.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)) return "android"
        return file.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { "android" } ?: "android"
    }

    private fun projectDirectory(name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") return null
        val root = File(context.filesDir, "generated_projects").canonicalFile
        val dir = File(root, sanitizeProjectName(name)).canonicalFile
        return dir.takeIf { it.parentFile == root }
    }

    /** Имена всех когда-либо сгенерированных проектов — самые новые последними. */
    fun listProjectNames(): List<String> =
        File(context.filesDir, "generated_projects")
            .listFiles { f -> f.isDirectory }
            ?.sortedBy { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()

    /** Простой .gitignore по угаданному стеку — чтобы `git init && git add -A &&
     *  git commit` в Termux сразу не утащил венвы/node_modules/секреты в историю.
     *  Не трогаем, если LLM уже сгенерировала свой .gitignore. */
    private fun ensureGitignore(projectDir: File, files: List<ProjectFile>) {
        val gitignoreFile = File(projectDir, ".gitignore")
        if (gitignoreFile.exists()) return

        val looksPython = files.any { it.relativePath.endsWith(".py") || it.relativePath == "requirements.txt" }
        val looksNode = files.any { it.relativePath.endsWith(".js") || it.relativePath == "package.json" }

        val lines = buildList {
            add("# Добавлено автоматически VoiceAgent")
            add(".env")
            add("*.log")
            if (looksPython) {
                add("__pycache__/")
                add("*.pyc")
                add(".venv/")
            }
            if (looksNode) {
                add("node_modules/")
            }
        }
        gitignoreFile.writeText(lines.joinToString("\n"))
    }

    private fun zipProject(projectDir: File, projectName: String): File {
        val zipFile = File(context.filesDir, "generated_projects/$projectName.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            projectDir.walkTopDown().filter { it.isFile && it.name != PLATFORM_META_FILE }.forEach { file ->
                val entryName = file.relativeTo(projectDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun copyToDownloads(zip: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceAgent")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VoiceAgent")
            dir.mkdirs()
            zip.copyTo(File(dir, displayName), overwrite = true)
        }
    }

    /** Share sheet intent so the user can send the zip straight to Termux, "Saved Messages", etc. */
    fun shareIntent(zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private const val PLATFORM_META_FILE = ".voiceagent-platform"
    }
}

