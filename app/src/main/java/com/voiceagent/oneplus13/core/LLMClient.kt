package com.voiceagent.oneplus13.core

import android.content.Context
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.voiceagent.oneplus13.BuildConfig
import com.voiceagent.oneplus13.core.security.SecretsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class ChatMessage(val role: String, val content: String)

// DeepSeek — OpenAI-совместимый формат: system идёт первым сообщением в messages.
@JsonClass(generateAdapter = true)
private data class DeepSeekRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.4,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
private data class DeepSeekChoice(val message: ChatMessage)

@JsonClass(generateAdapter = true)
private data class DeepSeekResponse(@Json(name = "choices") val choices: List<DeepSeekChoice>)

// Claude (Anthropic Messages API) — другой формат: system — отдельное поле,
// а не сообщение с role="system" внутри messages.
@JsonClass(generateAdapter = true)
private data class ClaudeRequest(
    val model: String,
    @Json(name = "max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ChatMessage>
)

@JsonClass(generateAdapter = true)
private data class ClaudeContentBlock(val type: String, val text: String? = null)

@JsonClass(generateAdapter = true)
private data class ClaudeResponse(val content: List<ClaudeContentBlock>)

/**
 * "Мозг" за AgentOrchestrator: превращает транскрипт + контекст либо в
 * голосовой ответ, либо в структурированный запрос на действие.
 *
 * Никакого ручного выбора "головы" в Настройках нет — маршрутизация
 * автоматическая, по сложности конкретного запроса:
 *
 * - Простой запрос -> только DeepSeek-chat (V3), один вызов, быстро и дёшево.
 * - Сложный запрос (см. [isComplexTask], либо вызывающий код явно попросил
 *   через [forceComplex] — так делает генерация кода) -> сперва черновик
 *   от DeepSeek-R1 (сильнее рассуждает, но медленнее), а если в Настройках
 *   есть ключ Claude — черновик уходит на проверку/исправление ошибок в
 *   Claude, и уже её ответ возвращается как финальный. Если ключа Claude
 *   нет — просто возвращаем черновик R1, деградация без ошибки.
 *
 * Предпочитает ключи, введённые в Настройках (SecretsStore), над тем, что
 * зашито в local.properties при сборке — так их можно сменить без пересборки.
 * Ретраит временные сетевые сбои с небольшой паузой — это критический путь
 * для каждой голосовой команды.
 */
class LLMClient(
    context: Context? = null,
    private val maxRetries: Int = 2
) {
    private val secrets = context?.let { SecretsStore(it) }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val transport = RetryingHttpClient(http, maxRetries)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val deepSeekRequestAdapter = moshi.adapter(DeepSeekRequest::class.java)
    private val deepSeekResponseAdapter = moshi.adapter(DeepSeekResponse::class.java)
    private val claudeRequestAdapter = moshi.adapter(ClaudeRequest::class.java)
    private val claudeResponseAdapter = moshi.adapter(ClaudeResponse::class.java)

    /**
     * @param forceComplex — не полагаться на автоопределение и сразу считать
     *   задачу сложной (R1 + проверка Claude). Используется генерацией кода
     *   ([com.voiceagent.oneplus13.devassist.CodeGenAgent]) — там цена ошибки
     *   выше, чем цена лишнего вызова.
     */
    suspend fun complete(
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String,
        forceComplex: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val complex = forceComplex || isComplexTask(userText)
        val draftModel = if (complex) "deepseek-reasoner" else "deepseek-chat"
        val draft = completeDeepSeek(draftModel, systemPrompt, history, userText)

        if (!complex) return@withContext draft

        val claudeKey = secrets?.get(SecretsStore.KEY_CLAUDE_API_KEY).orEmpty()
        if (claudeKey.isBlank()) return@withContext draft

        // Проверка черновика — best effort: если у Claude сбой (сеть, лимит,
        // невалидный ключ), не роняем весь ответ, а тихо отдаём черновик R1.
        try {
            reviewWithClaude(claudeKey, systemPrompt, userText, draft)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("LLMClient", "Claude review failed; using DeepSeek response", error)
            draft
        }
    }

    /**
     * Грубая, но дешёвая (без лишнего сетевого вызова) эвристика сложности.
     * Смысл не в идеальной точности, а в том, чтобы не гонять каждое "привет"
     * через R1 + Claude — это медленнее и дороже без видимой пользы.
     */
    private fun isComplexTask(userText: String): Boolean {
        val normalized = userText.lowercase()
        val complexSignals = listOf(
            "почему", "объясни", "докажи", "вычисли", "посчитай", "сравни",
            "спланируй", "оптимизируй", "алгоритм", "архитектур", "почини",
            "исправь ошибку", "проанализируй", "стратеги", "разбер"
        )
        val hasComplexSignal = complexSignals.any { normalized.contains(it) }
        val isLong = userText.length > 220
        val hasMultipleSteps = Regex("(затем|после этого|и потом|шаг \\d|во-первых|во-вторых)")
            .containsMatchIn(normalized)
        return hasComplexSignal || isLong || hasMultipleSteps
    }

    /**
     * Прямой вопрос к Claude в обход классификатора и черновика DeepSeek — для
     * "спроси Клода ...". Тратит токены только на один вызов Claude, а не два
     * (DeepSeek-черновик + Claude-проверка), что дороже, чем нужно для случая,
     * когда пользователь и так явно попросил именно Claude, а не "проверку".
     */
    suspend fun askClaudeDirect(systemPrompt: String, history: List<ChatMessage>, userText: String): String =
        withContext(Dispatchers.IO) {
            val claudeKey = secrets?.get(SecretsStore.KEY_CLAUDE_API_KEY).orEmpty()
            if (claudeKey.isBlank()) {
                // Деградация: нет ключа Claude в Настройках — не роняем команду,
                // просто отвечаем обычным (сложным) путём через DeepSeek.
                return@withContext complete(systemPrompt, history, userText, forceComplex = true)
            }

            val body = claudeRequestAdapter.toJson(
                ClaudeRequest(
                    model = "claude-sonnet-5",
                    maxTokens = 8192,
                    system = systemPrompt,
                    messages = history + ChatMessage("user", userText)
                )
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", claudeKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(body)
                .build()

            transport.execute(request) { json ->
                claudeResponseAdapter.fromJson(json)?.content?.firstOrNull { it.type == "text" }?.text
            }
        }

    private suspend fun completeDeepSeek(model: String, systemPrompt: String, history: List<ChatMessage>, userText: String): String {
        val apiKey = secrets?.getOrDefault(SecretsStore.KEY_DEEPSEEK_API_KEY, BuildConfig.DEEPSEEK_API_KEY)
            ?: BuildConfig.DEEPSEEK_API_KEY
        require(apiKey.isNotBlank()) { "DeepSeek API key is not set (see Settings or local.properties)" }

        val messages = buildList {
            add(ChatMessage("system", systemPrompt))
            addAll(history)
            add(ChatMessage("user", userText))
        }
        val body = deepSeekRequestAdapter.toJson(DeepSeekRequest(model = model, messages = messages))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return transport.execute(request) { json ->
            deepSeekResponseAdapter.fromJson(json)?.choices?.firstOrNull()?.message?.content
        }
    }

    /** Не отвечает "с нуля" — проверяет и правит черновик от DeepSeek-R1. */
    private suspend fun reviewWithClaude(apiKey: String, originalSystemPrompt: String, userText: String, draft: String): String {
        val reviewSystem = """
            $originalSystemPrompt

            Дополнительно: ниже — черновой ответ другой модели (DeepSeek-R1) на
            тот же запрос пользователя. Проверь его на фактические, логические
            и синтаксические ошибки и исправь их. Если черновик — JSON
            (структура проекта с файлами кода, JSON-действие и т.п.) — строго
            сохрани тот же формат и набор полей, не добавляй пояснений вне
            JSON и НЕ оборачивай ответ в ```` ```json ```` или любые другие
            обратные кавычки — только сырой JSON. Если черновик уже верный —
            верни его как есть, не переписывай без нужды. Отвечай только
            финальным исправленным текстом, без комментариев о том, что ты
            его проверял.
        """.trimIndent()

        val reviewUserText = "Запрос пользователя: $userText\n\nЧерновой ответ на проверку:\n$draft"
        val body = claudeRequestAdapter.toJson(
            ClaudeRequest(
                model = "claude-sonnet-5",
                maxTokens = 8192,
                system = reviewSystem,
                messages = listOf(ChatMessage("user", reviewUserText))
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body)
            .build()

        return transport.execute(request) { json ->
            claudeResponseAdapter.fromJson(json)?.content?.firstOrNull { it.type == "text" }?.text
        }
    }

}

