package com.voiceagent.oneplus13.system

/** The spoken digest uses only counts by sender app. Never retain notification text here. */
object NotificationBuffer {
    private const val MAX_ENTRIES = 200
    private val packages = ArrayDeque<String>()
    private var sequence = 0L

    data class Snapshot(val sequence: Long, val counts: Map<String, Int>)

    @Synchronized
    fun record(packageName: String) {
        if (packages.size == MAX_ENTRIES) packages.removeFirst()
        packages.addLast(packageName)
        sequence++
    }

    @Synchronized
    fun since(previous: Long): Snapshot {
        val count = (sequence - previous).coerceIn(0L, packages.size.toLong()).toInt()
        val counts = mutableMapOf<String, Int>()
        for (i in packages.size - count until packages.size) {
            val source = packages[i]
            counts[source] = counts.getOrDefault(source, 0) + 1
        }
        return Snapshot(sequence, counts)
    }
}
