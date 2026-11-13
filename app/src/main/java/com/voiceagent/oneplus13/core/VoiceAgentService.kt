package com.voiceagent.oneplus13.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.voiceagent.oneplus13.R
import com.voiceagent.oneplus13.VoiceAgentApp
import com.voiceagent.oneplus13.agent.AgentOrchestrator
import com.voiceagent.oneplus13.overlay.OverlayBubbleController
import com.voiceagent.oneplus13.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Always-on foreground service: keeps the microphone pipeline (STT) and the
 * orchestrator alive while the app is backgrounded, per Android's foreground
 * service rules for microphone access.
 *
 * Также хозяин системного оверлей-пузыря ([OverlayBubbleController]) — он
 * специально живёт здесь, а не в MainActivity, чтобы напрямую дёргать
 * `orchestrator` без bindService и продолжать работать, даже если экран
 * приложения закрыт (пузырь виден поверх любого другого приложения).
 */
class VoiceAgentService : Service() {

    private val job = SupervisorJob()
    private val errorHandler = CoroutineExceptionHandler { _, error ->
        android.util.Log.e("VoiceAgentService", "Service coroutine failed", error)
    }
    private val scope = CoroutineScope(job + errorHandler)

    private lateinit var stt: STTEngine
    private lateinit var tts: TTSEngine
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var uiPrefs: UiPreferences

    private var overlay: OverlayBubbleController? = null
    private var conversationActiveUntil = 0L
    private var initFailed = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_listening)))
        try {
            stt = STTEngine(applicationContext)
            tts = TTSEngine(applicationContext)
            orchestrator = AgentOrchestrator(applicationContext, LLMClient(applicationContext), tts, scope)
            uiPrefs = UiPreferences(applicationContext)
        } catch (error: Exception) {
            initFailed = true
            android.util.Log.e("VoiceAgentService", "Initialization failed", error)
            stopSelf()
            return
        }

        stt.ensureModel(
            onReady = { startListeningLoop() },
            onError = { scope.launch { AgentEventBus.publish(AgentEvent.Error("Модель STT не загрузилась", it)) } }
        )

        // Если пользователь раньше включил оверлей и разрешение уже выдано —
        // поднимаем пузырь сразу вместе с сервисом, без повторного тапа в UI.
        if (uiPrefs.overlayEnabled && OverlayBubbleController.canDrawOverlays(applicationContext)) {
            startOverlay()
        }

        // Готовый проект — отдельное (не "вечное") уведомление с кнопками
        // "Открыть"/"Поделиться", чтобы забрать zip можно было сразу, даже
        // если экран приложения сейчас закрыт.
        scope.launch {
            AgentEventBus.events.collect { event ->
                if (event is AgentEvent.ProjectReady) postProjectReadyNotification(event)
            }
        }
    }

    private fun startListeningLoop() {
        scope.launch {
            stt.listen { tts.acceptsMicrophoneInput() }.collect { transcript ->
                if (!tts.acceptsMicrophoneInput()) return@collect
                val gotWakeWord = containsWakeWord(transcript)
                if (gotWakeWord) {
                    AgentEventBus.publish(AgentEvent.WakeDetected)
                    overlay?.flashOnWake()
                }

                // Реагируем только на будильное слово или пока "разговор" ещё
                // не остыл (CONVERSATION_WINDOW_MS после последней фразы) — это
                // и есть "появляется только по вызову", а не слушает каждое
                // произнесённое слово в комнате как команду.
                if (gotWakeWord || isConversationActive()) {
                    conversationActiveUntil = System.currentTimeMillis() + CONVERSATION_WINDOW_MS
                    val cleaned = stripWakeWord(transcript)
                    if (cleaned.isNotBlank()) {
                        AgentEventBus.publish(AgentEvent.Heard(cleaned))
                        orchestrator.handleTranscript(cleaned)
                    }
                }
            }
        }
    }

    private fun isConversationActive(): Boolean = System.currentTimeMillis() < conversationActiveUntil

    private fun containsWakeWord(text: String): Boolean =
        WAKE_WORDS.any { text.contains(it, ignoreCase = true) }

    private fun stripWakeWord(text: String): String {
        var result = text
        WAKE_WORDS.forEach { result = result.replace(it, "", ignoreCase = true) }
        return result.trim()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (initFailed) START_NOT_STICKY else START_STICKY

    /** Включает/выключает системный оверлей-пузырь и запоминает выбор. Ничего
     *  не делает, если разрешение "поверх других приложений" ещё не выдано —
     *  UI должен сначала направить пользователя в системные настройки. */
    fun setOverlayEnabled(enabled: Boolean) {
        uiPrefs.overlayEnabled = enabled
        if (enabled) {
            if (OverlayBubbleController.canDrawOverlays(applicationContext)) startOverlay()
        } else {
            stopOverlay()
        }
    }

    fun isOverlayShowing(): Boolean = overlay?.isShowing() == true

    /** Включает/выключает "висит постоянно" против "появляется только по
     *  будильному слову" — применяется сразу, без перезапуска сервиса. */
    fun setOverlayAlwaysVisible(alwaysVisible: Boolean) {
        uiPrefs.overlayAlwaysVisible = alwaysVisible
        if (alwaysVisible) overlay?.show() else overlay?.hide()
    }

    fun isOverlayAlwaysVisible(): Boolean = uiPrefs.overlayAlwaysVisible

    private fun startOverlay() {
        if (overlay != null) return
        overlay = OverlayBubbleController(
            context = applicationContext,
            scope = scope,
            onSendText = { text -> scope.launch { orchestrator.handleTranscript(text) } }
        ).also {
            // По умолчанию пузырь появляется только по будильному слову
            // (flashOnWake из startListeningLoop). Явная настройка "показывать
            // всегда" возвращает старое поведение постоянно видимого пузыря.
            if (uiPrefs.overlayAlwaysVisible) it.show()
        }
    }

    private fun stopOverlay() {
        overlay?.hide()
        overlay = null
    }

    /**
     * Позволяет UI (MainScreen -> набранный текст в панели чата) отправлять
     * запрос в тот же оркестратор, что обрабатывает голос — без дублирования
     * логики распознавания намерений/действий.
     */
    inner class LocalBinder : Binder() {
        fun sendText(text: String) {
            scope.launch { orchestrator.handleTranscript(text) }
        }

        fun setOverlayEnabled(enabled: Boolean) = this@VoiceAgentService.setOverlayEnabled(enabled)
        fun isOverlayShowing(): Boolean = this@VoiceAgentService.isOverlayShowing()
        fun setOverlayAlwaysVisible(alwaysVisible: Boolean) = this@VoiceAgentService.setOverlayAlwaysVisible(alwaysVisible)
        fun isOverlayAlwaysVisible(): Boolean = this@VoiceAgentService.isOverlayAlwaysVisible()
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? = if (initFailed) null else binder

    override fun onDestroy() {
        stopOverlay()
        job.cancel()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, VoiceAgentApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    /** Отдельное уведомление на каждый готовый проект — с прямыми кнопками
     *  "Открыть"/"Поделиться", без похода в файловый менеджер вручную. */
    private fun postProjectReadyNotification(event: AgentEvent.ProjectReady) {
        val file = File(event.zipPath)
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val shareChooser = Intent.createChooser(shareIntent, "Поделиться проектом").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPending = PendingIntent.getActivity(this, event.projectName.hashCode(), openIntent, flags)
        val sharePending = PendingIntent.getActivity(this, event.projectName.hashCode() + 1, shareChooser, flags)

        val notification = NotificationCompat.Builder(this, VoiceAgentApp.CHANNEL_ID)
            .setContentTitle("Проект «${event.projectName}» готов")
            .setContentText("${file.name} — нажмите, чтобы открыть")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(0, "Открыть", openPending)
            .addAction(0, "Поделиться", sharePending)
            .build()

        NotificationManagerCompat.from(this).notify(event.projectName.hashCode(), notification)
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CONVERSATION_WINDOW_MS = 20_000L
        private val WAKE_WORDS = listOf("ваня", "эй ваня")
    }
}

