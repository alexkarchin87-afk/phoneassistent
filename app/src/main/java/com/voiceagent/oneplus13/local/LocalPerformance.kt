package com.voiceagent.oneplus13.local

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.Executor

enum class LocalPerformance(val label: String, val threads: Int) {
    COOL("Прохладнее", 2), BALANCED("Сбалансированно", 3), FAST("Быстрее", 4);

    companion object {
        fun read(context: Context): LocalPerformance = entries.firstOrNull {
            it.name == context.getSharedPreferences("local_performance", Context.MODE_PRIVATE).getString("profile", BALANCED.name)
        } ?: BALANCED
        fun save(context: Context, value: LocalPerformance) {
            context.getSharedPreferences("local_performance", Context.MODE_PRIVATE).edit().putString("profile", value.name).apply()
        }
    }
}

internal data class LocalWorkLimits(val threads: Int, val delayMs: Int)

/** Android's status is a throttling signal, not a temperature reading. */
internal fun localWorkLimits(profile: LocalPerformance, thermalStatus: Int, cpuCount: Int): LocalWorkLimits {
    val available = cpuCount.coerceAtLeast(1)
    val base = profile.threads.coerceAtMost(available)
    return when {
        thermalStatus >= 4 -> LocalWorkLimits(1, 250)
        thermalStatus >= 3 -> LocalWorkLimits(1, 100)
        thermalStatus >= 2 -> LocalWorkLimits(base.coerceAtMost(2), 20)
        else -> LocalWorkLimits(base, 0)
    }
}

/** Lives only while an inference request is running, including voice and bot calls. */
internal class ThermalMonitor(context: Context, private val update: (LocalWorkLimits) -> Unit) : AutoCloseable {
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val profile = LocalPerformance.read(context)
    private val monitorLock = Any()
    private var live = true
    private val listener = if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { status ->
        synchronized(monitorLock) { if (live) apply(status) }
    } else null

    init {
        val current = if (Build.VERSION.SDK_INT >= 29) runCatching { power?.currentThermalStatus ?: 0 }.getOrDefault(0) else 0
        apply(current)
        if (Build.VERSION.SDK_INT >= 29) {
            listener?.let { registered -> runCatching { power?.addThermalStatusListener(Executor { it.run() }, registered) } }
        }
    }

    private fun apply(status: Int) = update(localWorkLimits(profile, status, Runtime.getRuntime().availableProcessors()))

    override fun close() {
        synchronized(monitorLock) { live = false }
        if (Build.VERSION.SDK_INT >= 29) listener?.let { registered -> runCatching { power?.removeThermalStatusListener(registered) } }
    }
}
