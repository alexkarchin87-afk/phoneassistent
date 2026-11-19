package com.voiceagent.oneplus13.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, exact offline commands. Unmatched text continues through the normal safe router. */
internal fun localAnswer(text: String, now: Date = Date()): String? {
    val normalized = text.trim().lowercase(Locale.ROOT).trimEnd('?', '!', '.', ' ')
    return when (normalized) {
        "который час", "сколько времени", "сколько сейчас времени", "время" ->
            "Сейчас " + SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(now)
        "какой сегодня день", "какая сегодня дата", "какое сегодня число", "сегодняшняя дата" ->
            "Сегодня " + SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru", "RU")).format(now)
        else -> null
    }
}

internal fun friendlyAssistantError(error: Throwable): String {
    val detail = error.message.orEmpty()
    return when {
        detail.contains("LOCAL_MODEL_MISSING") -> "Локальная модель не найдена. Проверь её в настройках мозга."
        detail.contains("LOCAL_CLAUDE_DISABLED") -> "Сейчас выбран локальный режим. Чтобы спросить Claude, переключи мозг в облачный режим."
        detail.contains("LOCAL_PROMPT_TOO_LONG") -> "Запрос слишком длинный для локальной модели. Попробуй короче."
        detail.contains("LOCAL_") -> "Локальной модели не удалось ответить. Проверь файл модели и свободную память телефона."
        detail.contains("DeepSeek API key is not set") -> "Добавь ключ DeepSeek API в настройках: Ключи и диагностика."
        detail.contains("HTTP 401") || detail.contains("HTTP 403") -> "DeepSeek отклонил ключ. Проверь его в настройках."
        detail.contains("HTTP 402") -> "DeepSeek сообщает о нехватке средств. Проверь баланс API."
        detail.contains("HTTP 429") -> "DeepSeek временно ограничил запросы. Попробуй чуть позже."
        error is java.io.IOException -> "Нет связи с сервисом ИИ. Проверь интернет и попробуй снова."
        else -> "Не удалось обработать команду. Попробуй ещё раз."
    }
}
