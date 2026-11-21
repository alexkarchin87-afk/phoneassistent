package com.voiceagent.oneplus13.workflow

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val workflowIdPattern = Regex("[A-Za-z0-9_-]{1,80}")
internal fun validWorkflowId(id: String): Boolean = workflowIdPattern.matches(id)

class WorkflowStorage(context: Context) {
    private val files = synchronized(WorkflowStorage::class.java) {
        val directory = File(context.applicationContext.filesDir, "workflows")
        stores.getOrPut(directory) { WorkflowFiles(directory) }
    }

    fun save(workflow: Workflow) = files.save(workflow)
    fun load(id: String): Workflow? = files.load(id)
    fun delete(id: String) = files.delete(id)
    fun listAll(): List<Workflow> = files.listAll()
    fun findByTriggerIn(text: String): Workflow? = files.findByTriggerIn(text)
    fun findByName(name: String): Workflow? = files.findByName(name)

    private companion object {
        val stores = mutableMapOf<File, WorkflowFiles>()
    }
}

/** Shared by the editor and voice service. Cache only lookup fields, never mutable blocks. */
internal class WorkflowFiles(
    private val directory: File,
    private val readFile: (File) -> String = { it.readText() }
) {
    private data class Entry(val file: File, val name: String, val trigger: String?)
    private var index: List<Entry>? = null

    @Synchronized fun save(workflow: Workflow) {
        require(validWorkflowId(workflow.id)) { "Invalid workflow id" }
        Files.createDirectories(directory.toPath())
        val target = File(directory, "${workflow.id}.json").toPath()
        val temporary = Files.createTempFile(directory.toPath(), "workflow-", ".tmp")
        try {
            temporary.toFile().writeText(workflow.toJson().toString())
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            index = null
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized fun delete(id: String) {
        if (validWorkflowId(id) && File(directory, "$id.json").delete()) index = null
    }

    @Synchronized fun load(id: String): Workflow? {
        if (!validWorkflowId(id)) return null
        return read(File(directory, "$id.json"))
    }

    private fun read(file: File): Workflow? = try {
        Workflow.fromJson(JSONObject(readFile(file)))
    } catch (_: java.io.IOException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }

    private fun storedFiles() = directory.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()

    @Synchronized fun listAll(): List<Workflow> = storedFiles().mapNotNull(::read).sortedBy { it.name.lowercase() }

    private fun entries(): List<Entry> = index ?: storedFiles().mapNotNull { file ->
        read(file)?.let { Entry(file, it.name, it.triggerPhrase?.lowercase()?.takeIf(String::isNotBlank)) }
    }.sortedBy { it.name.lowercase() }.also { index = it }

    @Synchronized fun findByTriggerIn(text: String): Workflow? {
        val normalized = text.lowercase()
        return entries().firstOrNull { it.trigger != null && normalized.contains(it.trigger) }?.let { read(it.file) }
    }

    @Synchronized fun findByName(name: String): Workflow? {
        val entries = entries()
        val match = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: entries.firstOrNull { it.name.contains(name, ignoreCase = true) }
        return match?.let { read(it.file) }
    }
}
