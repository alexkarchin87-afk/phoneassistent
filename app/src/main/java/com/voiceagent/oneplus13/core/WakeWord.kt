package com.voiceagent.oneplus13.core

/** Match whole words, preferring the longer form, and keep the rest of the phrase. */
internal object WakeWord {
    private val expression = Regex(
        "(?<![\\p{L}\\p{N}_])(?:эй\\s+ваня|ваня)(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE
    )

    fun contains(text: String): Boolean = expression.containsMatchIn(text)

    fun strip(text: String): String =
        expression.replace(text, "").trim(' ', ',', '.', '!', '?', ':', ';', '—', '-')
}
