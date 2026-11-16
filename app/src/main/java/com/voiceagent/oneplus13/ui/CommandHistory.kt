package com.voiceagent.oneplus13.ui

import android.content.Context

data class HistoryItem(val text: String, val timestamp: Long)

/** Recent requests are kept only for this screen session. */
class CommandHistory(@Suppress("UNUSED_PARAMETER") context: Context) {
    private var items: List<HistoryItem> = emptyList()

    fun all(): List<HistoryItem> = items

    fun add(text: String): List<HistoryItem> {
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            items = (listOf(HistoryItem(clean, System.currentTimeMillis())) +
                items.filterNot { it.text.equals(clean, ignoreCase = true) }).take(30)
        }
        return items
    }

    fun clear(): List<HistoryItem> {
        items = emptyList()
        return items
    }
}
