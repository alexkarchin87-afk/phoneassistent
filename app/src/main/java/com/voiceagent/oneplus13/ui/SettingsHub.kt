package com.voiceagent.oneplus13.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Полноэкранные настройки (вместо прежнего AlertDialog по центру): агент, оверлей-пузырь,
 * системные разрешения, вид интерфейса и переходы в «Ключи и токены» / «Мастерскую».
 * Те же [AgentControls], что и раньше, — логика вызовов не менялась.
 */
@Composable
fun SettingsHubScreen(
    controls: AgentControls,
    currentOverride: FormFactorOverride,
    onOverrideChange: (FormFactorOverride) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Разрешение «поверх приложений» выдаётся в системных настройках — перечитываем при возврате.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasOverlayPermission = remember(tick) { controls.canDrawOverlays() }
    var overlayEnabled by remember(tick) { mutableStateOf(controls.isOverlayEnabled()) }
    var alwaysVisible by remember(tick) { mutableStateOf(controls.isOverlayAlwaysVisible()) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = "Настройки", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsColumn {
                SettingsGroup(title = "Агент") {
                    SettingsRow(
                        icon = VaIcons.Power,
                        title = "Голосовой агент",
                        subtitle = if (controls.isAgentRunning) "Работает и слушает слово «Ваня»" else "Выключен",
                        onClick = { if (controls.isAgentRunning) controls.onStopAgent() else controls.onStartAgent() },
                        trailing = {
                            VaSwitch(
                                checked = controls.isAgentRunning,
                                onCheckedChange = { if (it) controls.onStartAgent() else controls.onStopAgent() }
                            )
                        }
                    )
                }

                SettingsGroup(
                    title = "Плавающая искра",
                    description = "Пузырь поверх любого приложения, как чат-хед: тап — открыть чат, " +
                        "потянуть — переместить. Рут не нужен, разрешение выдаётся в системных настройках."
                ) {
                    if (!hasOverlayPermission) {
                        SettingsRow(
                            icon = VaIcons.Layers,
                            title = "Показ поверх приложений",
                            subtitle = "Разрешите в системных настройках, затем вернитесь сюда и включите пузырь.",
                            onClick = controls.onRequestOverlayPermission,
                            trailing = { ChevronTrailing() }
                        )
                    } else {
                        SettingsRow(
                            icon = VaIcons.Layers,
                            title = "Плавающий пузырь",
                            subtitle = if (overlayEnabled) "Включён" else "Выключен",
                            onClick = {
                                overlayEnabled = !overlayEnabled
                                controls.onSetOverlayEnabled(overlayEnabled)
                            },
                            trailing = {
                                VaSwitch(overlayEnabled) {
                                    overlayEnabled = it
                                    controls.onSetOverlayEnabled(it)
                                }
                            }
                        )
                        if (overlayEnabled) {
                            SettingsDivider()
                            SettingsRow(
                                icon = null,
                                title = "Всегда на экране",
                                subtitle = if (alwaysVisible) "Пузырь виден постоянно"
                                else "Появляется на пару секунд, когда услышит «Ваня», и прячется",
                                onClick = {
                                    alwaysVisible = !alwaysVisible
                                    controls.onSetOverlayAlwaysVisible(alwaysVisible)
                                },
                                trailing = {
                                    VaSwitch(alwaysVisible) {
                                        alwaysVisible = it
                                        controls.onSetOverlayAlwaysVisible(it)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsGroup(title = "Разрешения") {
                    SettingsRow(
                        icon = VaIcons.Accessibility,
                        title = "Спец. возможности",
                        subtitle = "Действия на экране. Передача экрана в модель пока отключена.",
                        onClick = controls.onOpenAccessibilitySettings,
                        trailing = { ChevronTrailing() }
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = VaIcons.Bell,
                        title = "Доступ к уведомлениям",
                        subtitle = "Для локальной сводки и быстрых ответов с подтверждением",
                        onClick = controls.onOpenNotificationSettings,
                        trailing = { ChevronTrailing() }
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = VaIcons.Battery,
                        title = "Оптимизация батареи",
                        subtitle = "Исключите приложение, иначе система усыпит фонового агента",
                        onClick = controls.onOpenBatterySettings,
                        trailing = { ChevronTrailing() }
                    )
                }

                SettingsGroup(
                    title = "Вид интерфейса",
                    description = "Авто определяет форму по размеру окна. Если запускаете через Windows " +
                        "Subsystem for Android и авто не сработало — включите Windows вручную."
                ) {
                    val options = listOf(
                        FormFactorOverride.AUTO to "Авто",
                        FormFactorOverride.PHONE to "Телефон",
                        FormFactorOverride.DESKTOP to "Боковая панель (планшет/DeX)",
                        FormFactorOverride.WINDOWS to "Windows (виджет в углу)"
                    )
                    options.forEachIndexed { index, (value, label) ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = if (index == 0) VaIcons.Monitor else null,
                            title = label,
                            onClick = { onOverrideChange(value) },
                            trailing = {
                                if (value == currentOverride) {
                                    Icon(VaIcons.Check, contentDescription = "Выбрано", modifier = Modifier.size(22.dp))
                                }
                            }
                        )
                    }
                }

                SettingsGroup(title = "Инструменты") {
                    SettingsRow(
                        icon = VaIcons.Flow,
                        title = "Мастерская сценариев",
                        subtitle = "Собирайте цепочки действий и запускайте голосом",
                        onClick = controls.onOpenWorkflowWorkshop,
                        trailing = { ChevronTrailing() }
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = VaIcons.Key,
                        title = "Ключи, память и диагностика",
                        subtitle = "API-ключи, найденные токены, что помнит агент, резервная копия",
                        onClick = controls.onOpenAppSettings,
                        trailing = { ChevronTrailing() }
                    )
                }

                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}
