package com.voiceagent.oneplus13.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.ui.SparkIconPro
import com.voiceagent.oneplus13.ui.theme.monoGradient

/**
 * Экран-замок перед основным UI. Показывается, пока [signedInEmail] == null.
 * Не блокирует голосовой сервис или оверлей на уровне системы — блокирует именно
 * открытие приложения, чтобы кто-то, взяв телефон в руки, не полез в настройки/секреты.
 * Логика и сигнатура прежние; изменён только внешний вид.
 */
@Composable
fun AccountGateScreen(
    signedInEmail: String?,
    errorMessage: String? = null,
    onSignInClick: () -> Unit,
    onContinueClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSkipClick: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .systemBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            SparkIconPro(size = 84.dp, animated = true)
            Spacer(Modifier.height(24.dp))
            Text("VoiceAgent", style = MaterialTheme.typography.headlineLarge.copy(brush = monoGradient()))
            Spacer(Modifier.height(10.dp))

            if (signedInEmail == null) {
                Text(
                    "Персональный голосовой ассистент. Войди через Google, чтобы продолжить.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = onSignInClick,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Войти через Google", style = MaterialTheme.typography.titleMedium) }

                if (errorMessage != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(errorMessage, color = cs.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    // Личный телефон одного человека: если Google-вход не настроен (например, не
                    // зарегистрирован SHA-1 сборки), лучше пустить владельца с предупреждением.
                    TextButton(onClick = onSkipClick) {
                        Text("Продолжить без входа (настрою вход позже)", color = cs.onSurfaceVariant)
                    }
                }
            } else {
                Text("Вы вошли как", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.surfaceContainer)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(signedInEmail, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = onContinueClick,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Продолжить", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onSignOutClick) {
                    Text("Выйти и сменить аккаунт", color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
