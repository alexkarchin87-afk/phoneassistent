package com.voiceagent.oneplus13

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.voiceagent.oneplus13.core.VoiceAgentService
import com.voiceagent.oneplus13.overlay.OverlayBubbleController
import com.voiceagent.oneplus13.ui.AgentControls
import com.voiceagent.oneplus13.ui.MainScreen
import com.voiceagent.oneplus13.ui.UiPreferences
import com.voiceagent.oneplus13.ui.theme.VoiceAgentTheme
import com.voiceagent.oneplus13.ui.workflow.WorkflowEditorActivity

/**
 * Точка входа. Реального взаимодействия с агентом здесь минимум — это делает
 * VoiceAgentService (голос) и AgentOrchestrator (логика). Activity отвечает
 * за: запуск/остановку
 * foreground-сервиса, привязку к нему (чтобы прокинуть набранный вручную
 * текст в тот же оркестратор), системные разрешения и (де)активацию
 * системного оверлей-пузыря (см. overlay/OverlayBubbleController.kt).
 */
class MainActivity : ComponentActivity() {

    private var binder: VoiceAgentService.LocalBinder? by mutableStateOf(null)
    private var isAgentRunning by mutableStateOf(false)

    private var serviceBound = false

    private val agentPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startVoiceAgentWithPermission()
        } else {
            isAgentRunning = false
            Toast.makeText(this, "Для голосового режима разреши доступ к микрофону.", Toast.LENGTH_LONG).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? VoiceAgentService.LocalBinder
            isAgentRunning = binder != null
            // Если пользователь раньше включил оверлей, а сервис только что
            // (пере)запустился — досинхронизируем фактическое состояние пузыря.
            if (UiPreferences(this@MainActivity).overlayEnabled) {
                binder?.setOverlayEnabled(true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            isAgentRunning = false
        }

        override fun onNullBinding(name: ComponentName?) {
            binder = null
            isAgentRunning = false
            if (serviceBound) { unbindService(this); serviceBound = false }
            Toast.makeText(this@MainActivity, "Не удалось запустить сервис. Проверь разрешения и попробуй снова.", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Если сервис уже был запущен раньше (например, после перезапуска
        // Activity), просто привязываемся к нему без повторного старта.
        if (isServiceRunning()) {
            bindToService()
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)

            VoiceAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                            windowSizeClass = windowSizeClass,
                            controls = AgentControls(
                                isAgentRunning = isAgentRunning,
                                onStartAgent = ::startVoiceAgent,
                                onStopAgent = ::stopVoiceAgent,
                                onSendText = { text ->
                                    val activeBinder = binder
                                    if (activeBinder == null) Toast.makeText(this, "Агент ещё запускается. Попробуй через несколько секунд.", Toast.LENGTH_SHORT).show()
                                    else activeBinder.sendText(text)
                                },
                                onOpenAccessibilitySettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onOpenNotificationSettings = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onOpenBatterySettings = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    }
                                },
                                onOpenAppSettings = {
                                    startActivity(Intent(this, SettingsActivity::class.java))
                                },
                                canDrawOverlays = { OverlayBubbleController.canDrawOverlays(this) },
                                onRequestOverlayPermission = {
                                    startActivity(OverlayBubbleController.overlayPermissionIntent(this))
                                },
                                isOverlayEnabled = {
                                    UiPreferences(this).overlayEnabled
                                },
                                onSetOverlayEnabled = { enabled ->
                                    UiPreferences(this).overlayEnabled = enabled
                                    if (binder == null && enabled) {
                                        // Пузырю нужен запущенный сервис (он живёт внутри него) —
                                        // если агент ещё не запущен, стартуем его вместе с пузырём.
                                        startVoiceAgent()
                                    }
                                    binder?.setOverlayEnabled(enabled)
                                },
                                isOverlayAlwaysVisible = {
                                    binder?.isOverlayAlwaysVisible() ?: UiPreferences(this).overlayAlwaysVisible
                                },
                                onSetOverlayAlwaysVisible = { alwaysVisible ->
                                    UiPreferences(this).overlayAlwaysVisible = alwaysVisible
                                    binder?.setOverlayAlwaysVisible(alwaysVisible)
                                },
                                onOpenWorkflowWorkshop = {
                                    startActivity(Intent(this, WorkflowEditorActivity::class.java))
                                }
                            )
                        )
                }
            }
        }
    }

    private fun startVoiceAgent() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            agentPermissions.launch(needed.toTypedArray())
        } else {
            startVoiceAgentWithPermission()
        }
    }

    private fun startVoiceAgentWithPermission() {
        val intent = Intent(this, VoiceAgentService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
            bindToService()
        } catch (error: SecurityException) {
            isAgentRunning = false
            Toast.makeText(this, "Не удалось запустить микрофон. Проверь разрешения приложения.", Toast.LENGTH_LONG).show()
        } catch (error: IllegalStateException) {
            isAgentRunning = false
            Toast.makeText(this, "Не удалось запустить сервис. Попробуй ещё раз из приложения.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVoiceAgent() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
            binder = null
        }
        stopService(Intent(this, VoiceAgentService::class.java))
        isAgentRunning = false
    }

    private fun bindToService() {
        if (!serviceBound) {
            serviceBound = bindService(Intent(this, VoiceAgentService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) Toast.makeText(this, "Не удалось подключиться к сервису.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == VoiceAgentService::class.java.name }
    }

    override fun onDestroy() {
        if (serviceBound) {
            runCatching { unbindService(connection) }
            serviceBound = false
        }
        super.onDestroy()
    }
}
