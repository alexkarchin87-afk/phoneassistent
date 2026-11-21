package com.voiceagent.oneplus13.core

/**
 * Модели (DeepSeek, а после проверки — и Claude) нередко оборачивают JSON-ответ
 * в ```` ```json ```` даже когда промпт прямо просит "без обрамления", а для
 * длинных ответов (генерация целого проекта) не редкость и пояснение до/после
 * самого JSON. Общая для [com.voiceagent.oneplus13.agent.AgentOrchestrator]
 * (короткие action-команды) и [com.voiceagent.oneplus13.devassist.CodeGenAgent]
 * (большие ответы с файлами проекта, включая черновик после ревью Claude).
 *
 * Возвращает null, если в тексте вообще нет сбалансированного JSON-объекта.
 */
fun extractJsonObject(text: String): String? {
    val unfenced = text.trim()
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = unfenced.indexOf('{')
    if (start == -1) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until unfenced.length) {
        val char = unfenced[i]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return unfenced.substring(start, i + 1)
            }
        }
    }
    return null
}
