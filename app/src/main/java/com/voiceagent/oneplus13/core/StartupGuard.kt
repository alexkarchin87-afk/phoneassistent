package com.voiceagent.oneplus13.core

import android.content.Context

/** Records only the last startup phase, so an interrupted start can be recovered without clearing app data. */
class StartupGuard(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_agent_startup", Context.MODE_PRIVATE)

    fun begin() = setStage("Подготовка сервиса")
    fun setStage(stage: String) { prefs.edit().putString("pending_stage", stage).commit() }
    fun clear() { prefs.edit().remove("pending_stage").commit() }
    fun consumeInterrupted(): String? {
        val stage = prefs.getString("pending_stage", null)
        if (stage != null) clear()
        return stage
    }
}
