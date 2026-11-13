package com.voiceagent.oneplus13.integrations.bot

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voiceagent.oneplus13.R
import com.voiceagent.oneplus13.VoiceAgentApp
import com.voiceagent.oneplus13.integrations.messenger.MaxClient
import com.voiceagent.oneplus13.integrations.messenger.MessengerBridge
import com.voiceagent.oneplus13.integrations.messenger.TelegramClient

/**
 * Foreground service that keeps MessengerBridge's long-polling loops alive so the
 * agent keeps receiving Telegram/MAX messages even while VoiceAgentService's own
 * screen isn't open. Kept as a separate service (rather than folded into
 * VoiceAgentService) so messaging can be toggled independently of voice listening.
 */
class TelegramBotService : Service() {

    private lateinit var bridge: MessengerBridge

    override fun onCreate() {
        super.onCreate()
        bridge = MessengerBridge(TelegramClient(applicationContext), MaxClient(applicationContext))
        startForeground(NOTIF_ID, buildNotification())
        bridge.startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 limits the total time a dataSync foreground service may run.
    override fun onTimeout(startId: Int, fgsType: Int) {
        bridge.stopPolling()
        stopSelf(startId)
    }

    override fun onDestroy() {
        bridge.stopPolling()
        super.onDestroy()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, VoiceAgentApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Слушаю сообщения (Telegram/MAX)")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIF_ID = 1002
    }
}

