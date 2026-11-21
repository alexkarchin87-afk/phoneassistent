package com.voiceagent.oneplus13.workflow

import android.content.Context
import org.json.JSONObject
import java.io.File

internal fun validWorkflowId(id: String): Boolean = Regex("[A-Za-z0-9_-]{1,80}").matches(id)

/** Персистентное хранение сценариев — по одному .json файлу на сценарий. */
class WorkflowStorage(private val context: Context) {

    private fun dir(): File = File(context.filesDir, "workflows").apply { mkdirs() }
    private fun fileFor(id: String): File {
        require(validWorkflowId(id)) { "Invalid workflow id" }
        return File(dir(), "$id.json")
    }

    fun save(workflow: Workflow) {
        fileFor(workflow.id).writeText(workflow.toJson().toString())
    }

    fun load(id: String): Workflow? {
        if (!validWorkflowId(id)) return null
        return fileFor(id).takeIf { it.exists() }
            ?.let { runCatching { Workflow.fromJson(JSONObject(it.readText())) }.getOrNull() }
    }

    fun delete(id: String) {
        if (validWorkflowId(id)) fileFor(id).delete()
    }

    fun listAll(): List<Workflow> =
        dir().listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { Workflow.fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Ищет сценарий по кодовой фразе внутри произвольного текста —
     *  используется для запуска без похода к LLM (см. AgentOrchestrator). */
    fun findByTriggerIn(text: String): Workflow? {
        val normalized = text.lowercase()
        return listAll().firstOrNull { wf ->
            val phrase = wf.triggerPhrase?.lowercase()
            !phrase.isNullOrBlank() && normalized.contains(phrase)
        }
    }

    fun findByName(name: String): Workflow? {
        val workflows = listAll()
        return workflows.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: workflows.firstOrNull { it.name.contains(name, ignoreCase = true) }
    }
}
