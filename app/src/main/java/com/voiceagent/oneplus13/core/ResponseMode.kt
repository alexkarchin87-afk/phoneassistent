package com.voiceagent.oneplus13.core

import android.content.Context

/** Shared by the chat, voice service and bot; explicit choice never sends local prompts to cloud. */
class ResponseMode(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("response_mode", Context.MODE_PRIVATE)
    var thoughtful: Boolean
        get() = prefs.getBoolean("thoughtful", false)
        set(value) { prefs.edit().putBoolean("thoughtful", value).apply() }
}
