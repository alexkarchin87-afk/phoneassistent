package com.voiceagent.oneplus13.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.voiceagent.oneplus13.MainActivity
import com.voiceagent.oneplus13.VoiceAgentApp
import com.voiceagent.oneplus13.core.VoiceAgentService

/**
 * Запускает основной голосовой сервис (и вместе с ним — плавающий пузырь,
 * см. VoiceAgentService/OverlayBubbleController) сразу после включения
 * телефона, без необходимости вручную открывать приложение.
 *
 * On Android 14+ a microphone foreground service cannot be started from boot.
 * Notify the user to open the app and start it visibly instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, VoiceAgentApp.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("VoiceAgent")
                .setContentText("Открой приложение и запусти голосового агента после перезагрузки.")
                .setContentIntent(open).setAutoCancel(true).build()
            try { context.getSystemService(NotificationManager::class.java).notify(302, notification) }
            catch (_: SecurityException) { /* Notifications may be disabled by the user. */ }
            return
        }
        try { context.startForegroundService(Intent(context, VoiceAgentService::class.java)) }
        catch (_: IllegalStateException) { /* Background start can still be restricted. */ }
        catch (_: SecurityException) { /* Required permission can have been revoked. */ }
    }
}
