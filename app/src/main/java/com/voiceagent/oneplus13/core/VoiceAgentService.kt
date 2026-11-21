package com.voiceagent.oneplus13.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean
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
    // Android views and overlay lifecycle always stay on the main thread.
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate + errorHandler)

    private lateinit var stt: STTEngine
    private lateinit var tts: TTSEngine
    private lateinit var orchestrator: Deferred<AgentOrchestrator>
    @Volatile private var orchestratorInstance: AgentOrchestrator? = null
    private lateinit var uiPrefs: UiPreferences

    private var overlay: OverlayBubbleController? = null
    @Volatile private var conversationActiveUntil = 0L
    private val commandBusy = AtomicBoolean(false)
    @Volatile private var initFailed = false
    private var initialized = false
    private lateinit var startupGuard: StartupGuard

    override fun onCreate() {
        super.onCreate()
        startupGuard = StartupGuard(applicationContext)
        uiPrefs = UiPreferences(applicationContext)
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_listening)))
        } catch (error: Exception) {
            initFailed = true
            android.util.Log.e("VoiceAgentService", "Foreground start failed", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A system restart without a user request must not restart a crashing pipeline.
        if (intent == null || initFailed) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!initialized) {
            initialized = true
            initializeAgent()
            startedInThisProcess = !initFailed
        }
        return START_NOT_STICKY
    }

    private fun initializeAgent() {
        startupGuard.begin()
        try {
            stt = STTEngine(applicationContext)
            tts = TTSEngine(applicationContext) { active -> stt.setSpeechOutputActive(active) }
            orchestrator = scope.async(Dispatchers.IO) {
                try {
                    AgentOrchestrator(applicationContext, LLMClient(applicationContext), tts, scope)
                        .also { orchestratorInstance = it }
                } catch (error: Exception) {
                    initFailed = true
                    startedInThisProcess = false
                    startupGuard.clear()
                    android.util.Log.e("VoiceAgentService", "Orchestrator initialization failed", error)
                    AgentEventBus.publish(AgentEvent.Error("Не удалось подготовить агента. Перезапусти приложение и проверь настройки.", error))
                    stopSelf()
                    throw error
                }
            }
        } catch (error: Exception) {
            initFailed = true
            startupGuard.clear()
            android.util.Log.e("VoiceAgentService", "Initialization failed", error)
            stopSelf()
            return
        }

        startupGuard.setStage("Проверка библиотек речи")
        scope.launch(Dispatchers.IO) {
            try {
                // StorageService creates Model on an unmanaged executor. Resolve native
                // libraries here first so linkage errors cannot crash that thread.
                Class.forName("org.vosk.LibVosk")
                startupGuard.setStage("Загрузка модели речи")
                withContext(Dispatchers.Main) {
                    stt.ensureModel(
                        onReady = { if (job.isActive) startListeningLoop() else stt.releaseModel() },
                        onError = { error ->
                            startupGuard.clear()
                            scope.launch { AgentEventBus.publish(AgentEvent.Error("Не удалось загрузить модель распознавания речи.", error)) }
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LinkageError) {
                startupGuard.clear()
                AgentEventBus.publish(AgentEvent.Error("Модуль распознавания речи не запустился на этом устройстве.", error))
            } catch (error: Exception) {
                startupGuard.clear()
                AgentEventBus.publish(AgentEvent.Error("Не удалось запустить распознавание речи.", error))
            }
        }

        // Если пользователь раньше включил оверлей и разрешение уже выдано —
        // поднимаем пузырь сразу вместе с сервисом, без повторного тапа в UI.
        if (uiPrefs.overlayEnabled && OverlayBubbleController.canDrawOverlays(applicationContext)) {
            try { startOverlay() } catch (error: Exception) {
                uiPrefs.overlayEnabled = false
                scope.launch { AgentEventBus.publish(AgentEvent.Error("Не удалось открыть плавающий пузырь. Он временно отключён.", error)) }
            }
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
            startupGuard.setStage("Запуск микрофона и распознавания")
            stt.listen(onStarted = { startupGuard.listenerStarted() }) { tts.acceptsMicrophoneInput() }
                .flowOn(Dispatchers.IO)
                .catch { error ->
                    if (error is CancellationException) throw error
                    startupGuard.clear()
                    AgentEventBus.publish(AgentEvent.Error("Не удалось включить распознавание речи. Проверь разрешение на микрофон.", error))
                }.collect { transcript ->
                if (!tts.acceptsMicrophoneInput()) return@collect
                val agent = orchestrator.await()
                val gotWakeWord = containsWakeWord(transcript)
                if (gotWakeWord) {
                    AgentEventBus.publish(AgentEvent.WakeDetected)
                    overlay?.flashOnWake()
                }

                // Реагируем только на будильное слово или пока "разговор" ещё
                // не остыл (CONVERSATION_WINDOW_MS после последней фразы) — это
                // и есть "появляется только по вызову", а не слушает каждое
                // произнесённое слово в комнате как команду.
                val route = routeTranscript(agent.isRecordingLecture(), gotWakeWord,
                    agent.isAwaitingConfirmation(), isConversationActive())
                if (route == TranscriptRoute.LECTURE) {
                    agent.appendLectureTranscript(transcript)
                    return@collect
                }
                if (route == TranscriptRoute.COMMAND) {
                    val cleaned = stripWakeWord(transcript)
                    if (cleaned.isNotBlank()) submitCommand(cleaned, voice = true)
                }
            }
        }
    }

    private fun isConversationActive(): Boolean = SystemClock.elapsedRealtime() < conversationActiveUntil

    private fun submitCommand(text: String, voice: Boolean = false) {
        if (text.isBlank()) return
        if (!::orchestrator.isInitialized || initFailed) {
            scope.launch { AgentEventBus.publish(AgentEvent.Error("Агент ещё не готов. Попробуй снова.")) }
            return
        }
        if (!commandBusy.compareAndSet(false, true)) {
            if (!voice) scope.launch { AgentEventBus.publish(AgentEvent.Error("Ваня ещё отвечает. Подожди и отправь сообщение снова.")) }
            return
        }
        conversationActiveUntil = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        scope.launch(Dispatchers.IO) {
            try {
                if (voice) AgentEventBus.publish(AgentEvent.Heard(text))
                orchestrator.await().handleTranscript(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AgentEventBus.publish(AgentEvent.Error("Не удалось обработать сообщение. Попробуй ещё раз.", error))
            } finally {
                conversationActiveUntil = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                commandBusy.set(false)
            }
        }
    }

    private fun containsWakeWord(text: String): Boolean =
        WakeWord.contains(text)

    private fun stripWakeWord(text: String): String = WakeWord.strip(text)

    /** Включает/выключает системный оверлей-пузырь и запоминает выбор. Ничего
     *  не делает, если разрешение "поверх других приложений" ещё не выдано —
     *  UI должен сначала направить пользователя в системные настройки. */
    fun setOverlayEnabled(enabled: Boolean) {
        uiPrefs.overlayEnabled = enabled
        if (enabled) {
            if (OverlayBubbleController.canDrawOverlays(applicationContext)) {
                try { startOverlay() } catch (error: Exception) {
                    uiPrefs.overlayEnabled = false
                    scope.launch { AgentEventBus.publish(AgentEvent.Error("Не удалось открыть плавающий пузырь. Он отключён.", error)) }
                }
            }
        } else {
            stopOverlay()
        }
    }

    fun isOverlayShowing(): Boolean = overlay?.isShowing() == true

    /** Включает/выключает "висит постоянно" против "появляется только по
     *  будильному слову" — применяется сразу, без перезапуска сервиса. */
    fun setOverlayAlwaysVisible(alwaysVisible: Boolean) {
        uiPrefs.overlayAlwaysVisible = alwaysVisible
        overlay?.setAlwaysVisible(alwaysVisible)
    }

    fun isOverlayAlwaysVisible(): Boolean = uiPrefs.overlayAlwaysVisible

    private fun startOverlay() {
        if (overlay != null) return
        overlay = OverlayBubbleController(
            context = applicationContext,
            scope = scope,
            onSendText = { text -> submitCommand(text) },
            alwaysVisible = { uiPrefs.overlayAlwaysVisible }
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
            submitCommand(text)
        }

        fun setOverlayEnabled(enabled: Boolean) = this@VoiceAgentService.setOverlayEnabled(enabled)
        fun isOverlayShowing(): Boolean = this@VoiceAgentService.isOverlayShowing()
        fun setOverlayAlwaysVisible(alwaysVisible: Boolean) = this@VoiceAgentService.setOverlayAlwaysVisible(alwaysVisible)
        fun isOverlayAlwaysVisible(): Boolean = this@VoiceAgentService.isOverlayAlwaysVisible()
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? = if (initFailed) null else binder

    override fun onDestroy() {
        startedInThisProcess = false
        stopOverlay()
        orchestratorInstance?.onServiceStopping()
        job.cancel()
        job.invokeOnCompletion {
            if (::stt.isInitialized) stt.releaseModel()
            startupGuard.clear()
        }
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
        // Activity and service share a process. A fresh process must not re-bind a
        // stale Android service record with BIND_AUTO_CREATE and resurrect it.
        @Volatile var startedInThisProcess: Boolean = false
            private set
        private const val NOTIF_ID = 1001
        private const val CONVERSATION_WINDOW_MS = 20_000L
    }
}
