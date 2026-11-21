package com.voiceagent.oneplus13.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Приоритет факта — по спецификации "Ваня": P1 навсегда (снять может только явное
 *  "забудь"), P2 долгосрочно (может быть заменён новым фактом на ту же тему), P3
 *  временно (само сжимается, когда фактов становится слишком много).
 *  rank выше = более постоянный факт — используем при слиянии дублей. */
enum class MemoryTier(val rank: Int) { P3(1), P2(2), P1(3) }

data class MemoryFact(val text: String, val tier: MemoryTier, val timestampMillis: Long)

/**
 * Лёгкое (не векторное, не LLM-суммаризация) кэширование фактов о пользователе
 * между разговорами — список коротких строк с приоритетом, который целиком
 * подставляется в системный промпт каждый раз (см. AgentOrchestrator.buildSystemPrompt).
 *
 * Приоритеты обеспечиваются кодом, а не просьбой к модели в промпте: LLM решает,
 * *какой* факт запомнить и с каким приоритетом, но правило "P1 нельзя стереть
 * автосжатием" гарантированно соблюдается здесь — ошибиться в подсчёте "не
 * превысил ли я лимит в 200 слов" в коде намного маловероятнее, чем понадеяться,
 * что модель всегда аккуратно перепишет markdown-файл по правилам.
 *
 * Хранится локально в SharedPreferences на самом телефоне — никуда не
 * отправляется, кроме как в промпт локально выбранной LLM.
 */
class UserMemory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Совместимость со старым API (BackupManager и старые вызовы) — по умолчанию P2. */
    fun remember(fact: String) = remember(fact, MemoryTier.P2)

    fun remember(fact: String, tier: MemoryTier) {
        val trimmed = fact.trim().take(MAX_FACT_LENGTH)
        if (trimmed.isBlank()) return

        val current = allDetailed().toMutableList()
        val existingIndex = current.indexOfFirst { it.text.equals(trimmed, ignoreCase = true) }
        val effectiveTier = if (existingIndex >= 0) {
            // Уже знаем этот факт — если раньше он был важнее (например P1), не понижаем.
            if (current[existingIndex].tier.rank > tier.rank) current[existingIndex].tier else tier
        } else tier
        if (existingIndex >= 0) current.removeAt(existingIndex)
        current.add(MemoryFact(trimmed, effectiveTier, System.currentTimeMillis()))

        save(enforceCapacity(current))
    }

    /** Убрать факт(ы), текст которых содержит [query] — например "забудь про возраст".
     *  Единственный способ убрать P1-факт, как и требует спецификация. */
    fun forget(query: String): Int {
        val q = query.trim().lowercase()
        if (q.isBlank()) return 0
        val current = allDetailed().toMutableList()
        val before = current.size
        current.removeAll { it.text.lowercase().contains(q) }
        save(current)
        return before - current.size
    }

    fun clear() = save(emptyList())

    fun allFacts(): List<String> = allDetailed().map { it.text }

    fun allDetailed(): List<MemoryFact> {
        val raw = prefs.getString(KEY_FACTS, null)
        if (raw != null) {
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MemoryFact(
                        text = o.getString("text"),
                        tier = runCatching { MemoryTier.valueOf(o.optString("tier", "P2")) }.getOrDefault(MemoryTier.P2),
                        timestampMillis = o.optLong("ts", 0L)
                    )
                }
            }.getOrDefault(emptyList())
        }
        // Миграция со старой (до приоритетов) плоской версии хранилища, если она есть.
        val legacy = prefs.getString(KEY_FACTS_LEGACY, null) ?: return emptyList()
        val migrated = runCatching {
            val arr = JSONArray(legacy)
            (0 until arr.length()).map { MemoryFact(arr.getString(it), MemoryTier.P2, System.currentTimeMillis()) }
        }.getOrDefault(emptyList())
        if (migrated.isNotEmpty()) save(migrated)
        return migrated
    }

    /** Готовый кусок для системного промпта — пусто, если ничего ещё не запомнено.
     *  Группируем по приоритету, чтобы модель понимала, что можно менять/забывать,
     *  а что — нет (P1 меняется только по прямой просьбе пользователя). */
    fun factsBlock(): String {
        val detailed = allDetailed()
        if (detailed.isEmpty()) return ""
        val p1 = detailed.filter { it.tier == MemoryTier.P1 }
        val p2 = detailed.filter { it.tier == MemoryTier.P2 }
        val p3 = detailed.filter { it.tier == MemoryTier.P3 }

        val sb = StringBuilder("Известные факты о пользователе (можно использовать в ответах):\n")
        if (p1.isNotEmpty()) sb.append("Навсегда: ").append(p1.joinToString("; ") { it.text }).append("\n")
        if (p2.isNotEmpty()) sb.append("Долгосрочно: ").append(p2.joinToString("; ") { it.text }).append("\n")
        if (p3.isNotEmpty()) sb.append("Сейчас актуально: ").append(p3.joinToString("; ") { it.text })
        return sb.toString().trim()
    }

    /** Если суммарный объём фактов превышает [MAX_TOTAL_WORDS] слов, в первую
     *  очередь выбрасываем самые старые P3-факты (временные — баги, музыка,
     *  сиюминутные детали), пока не впишемся в лимит. P1 и P2 автосжатием не
     *  трогаем — по спецификации P1 неприкосновенен, а P2 заменяется только явно
     *  той же темой, а не выкидывается автоматически по объёму. */
    private fun enforceCapacity(facts: List<MemoryFact>): List<MemoryFact> {
        val wordsByFact = mutableMapOf<MemoryFact, Int>()
        var remainingWords = 0
        for (fact in facts) {
            val words = WORD_SEPARATOR.split(fact.text).count { it.isNotBlank() }
            remainingWords += words
            wordsByFact[fact] = (wordsByFact[fact] ?: 0) + words
        }
        if (remainingWords <= MAX_TOTAL_WORDS) return facts

        val oldestP3First = facts.filter { it.tier == MemoryTier.P3 }.sortedBy { it.timestampMillis }
        val toDrop = mutableSetOf<MemoryFact>()
        for (fact in oldestP3First) {
            if (remainingWords <= MAX_TOTAL_WORDS) break
            if (toDrop.add(fact)) remainingWords -= wordsByFact.getValue(fact)
        }
        return facts.filterNot { it in toDrop }
    }

    private fun save(facts: List<MemoryFact>) {
        val arr = JSONArray()
        facts.forEach { f ->
            arr.put(JSONObject().apply {
                put("text", f.text)
                put("tier", f.tier.name)
                put("ts", f.timestampMillis)
            })
        }
        prefs.edit().putString(KEY_FACTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_agent_user_memory"
        private const val KEY_FACTS = "facts_v2"
        private const val KEY_FACTS_LEGACY = "facts"
        private const val MAX_FACT_LENGTH = 200
        private val WORD_SEPARATOR = Regex("\\s+")
        private const val MAX_TOTAL_WORDS = 200
    }
}
