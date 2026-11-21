package com.voiceagent.oneplus13.workflow

import com.voiceagent.oneplus13.core.ChatMessage
import com.voiceagent.oneplus13.core.LLMClient
import com.voiceagent.oneplus13.core.extractJsonObject
import org.json.JSONObject
import android.net.Uri

/**
 * "Режим ИИ" в редакторе сценариев: пользователь, который не разбирается в
 * блок-схемах, просто пишет словами, что должен делать сценарий — а модель
 * сама раскладывает это на цепочку блоков из [BlockType].
 *
 * Идёт через `forceComplex = true` — тот же путь, что и генерация кода
 * (DeepSeek-R1 черновик + проверка Claude, если ключ задан): ошибка здесь
 * не "неловкая фраза", а неверно выполненное системное действие на телефоне,
 * так что цена ошибки такая же высокая, как у кодогенерации.
 */
class WorkflowAiBuilder(private val llm: LLMClient) {

    suspend fun build(description: String, existingName: String? = null): Workflow {
        val prompt = """
            Ты помогаешь собрать сценарий автоматизации из готовых блоков для
            голосового ассистента на Android. Доступные типы блоков и их
            параметры (params):
            ${BlockType.entries.joinToString("\n") { "- ${it.name}: ${it.paramKeys.joinToString(", ")}" }}

            OPEN_APP.package — имя пакета Android (например "com.spotify.music")
            или, если не уверен в точном пакете, обычное имя приложения — система
            сама попробует найти по имени.
            SET_VOLUME.percent — число от 0 до 100.
            DELAY.seconds — целое число секунд, не больше 60.

            Ответь СТРОГО JSON вида:
            {"name": "короткое имя сценария", "blocks": [{"id": "b1", "type": "OPEN_APP", "params": {"package": "..."}}],
             "connections": [{"from": "b1", "to": "b2"}]}
            Блоки в сценарии должны идти строго один за другим (это линейная
            цепочка, без ветвлений) — количество connections на один меньше
            количества blocks. id — короткие произвольные строки, уникальные
            внутри сценария. Никакого текста до или после JSON, никаких
            markdown-обрамлений в ```` ``` ````.
        """.trimIndent()

        val raw = llm.complete(prompt, emptyList<ChatMessage>(), description, forceComplex = true)
        val candidate = extractJsonObject(raw)
            ?: throw IllegalStateException("ИИ ответил не JSON-ом при сборке сценария: ${raw.take(200)}")
        val json = JSONObject(candidate)

        val workflow = Workflow(name = existingName ?: json.optString("name", "Сценарий").ifBlank { "Сценарий" })

        val blocksJson = json.optJSONArray("blocks")
        require(blocksJson != null && blocksJson.length() in 1..5) { "Сценарий должен содержать от 1 до 5 блоков" }
        if (blocksJson != null) {
            for (i in 0 until blocksJson.length()) {
                val b = blocksJson.getJSONObject(i)
                val type = requireNotNull(BlockType.fromId(b.optString("type"))) { "Неизвестный тип блока" }
                val paramsJson = b.optJSONObject("params") ?: JSONObject()
                val params = paramsJson.keys().asSequence().associateWith { paramsJson.getString(it) }.toMutableMap()
                require(type.paramKeys.all { !params[it].isNullOrBlank() }) { "Не заполнены параметры блока" }
                when (type) {
                    BlockType.OPEN_URL -> {
                        val uri = Uri.parse(params.getValue("url"))
                        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Некорректная ссылка" }
                    }
                    BlockType.SET_VOLUME -> require(params["percent"]?.toIntOrNull()?.let { it in 0..100 } == true) { "Громкость должна быть от 0 до 100" }
                    BlockType.DELAY -> require(params["seconds"]?.toIntOrNull()?.let { it in 0..60 } == true) { "Пауза должна быть от 0 до 60 секунд" }
                    else -> Unit
                }
                workflow.blocks.add(
                    WorkflowBlock(
                        id = b.optString("id").ifBlank { "b${i + 1}" },
                        type = type,
                        params = params,
                        // Простая вертикальная раскладка — координаты модель не считает,
                        // это только стартовое расположение для редактора, пользователь
                        // может перетащить блоки как удобно.
                        x = 120f,
                        y = 120f + i * 220f
                    )
                )
            }
        }

        val connectionsJson = json.optJSONArray("connections")
        if (connectionsJson != null && connectionsJson.length() > 0) {
            for (i in 0 until connectionsJson.length()) {
                val c = connectionsJson.getJSONObject(i)
                val from = c.optString("from")
                val to = c.optString("to")
                require(workflow.blocks.any { it.id == from } && workflow.blocks.any { it.id == to }) { "Связь указывает на неизвестный блок" }
                workflow.connections.add(WorkflowConnection(from, to))
            }
        } else if (workflow.blocks.size > 1) {
            // Подстраховка: если модель забыла connections, но блоки перечислены
            // по порядку — соединяем их линейно сами, а не оставляем сценарий
            // из несвязанных блоков.
            for (i in 0 until workflow.blocks.size - 1) {
                workflow.connections.add(WorkflowConnection(workflow.blocks[i].id, workflow.blocks[i + 1].id))
            }
        }

        require(workflow.connections.size == workflow.blocks.size - 1 && describeWorkflowForConfirmation(workflow) != null) {
            "Сценарий должен быть одной линейной цепочкой"
        }
        return workflow
    }
}
