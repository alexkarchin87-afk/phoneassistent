package com.voiceagent.oneplus13.core

import android.content.Context

/** Records only the last startup phase, so an interrupted start can be recovered without clearing app data. */
class StartupGuard(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_agent_startup", Context.MODE_PRIVATE)

    fun begin() { prefs.edit().putString("pending_stage", "Подготовка сервиса").putBoolean("listener_started", false).commit() }
    fun setStage(stage: String) { prefs.edit().putString("pending_stage", stage).commit() }
    fun listenerStarted() { prefs.edit().remove("pending_stage").putBoolean("listener_started", true).commit() }
    fun clear() { prefs.edit().remove("pending_stage").commit() }
    fun consumeInterrupted(): String? {
        val stage = prefs.getString("pending_stage", null)
        if (stage != null) clear()
        return stage
    }
}
