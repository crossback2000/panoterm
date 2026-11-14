package dev.panoterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.panoterm.App
import dev.panoterm.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App, biometricAvailable: Boolean, onLicenses: () -> Unit, onBack: () -> Unit) {
    val v by app.settings.values.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section("보안")
            Toggle(
                "앱 열 때 생체인증", if (biometricAvailable) "지문/얼굴 인식으로 잠금 해제" else "이 기기에서 사용할 수 없음",
                v.biometricLock && biometricAvailable, enabled = biometricAvailable,
            ) { b -> app.settings.update { it.copy(biometricLock = b) } }

            Section("키보드 (K380 등)")
            Toggle("CapsLock을 Ctrl로", "CapsLock을 누르고 있는 동안 Ctrl로 동작", v.capsAsCtrl) { b ->
                app.settings.update { it.copy(capsAsCtrl = b) }
            }
            Text("하드웨어 키보드 한/영 전환 키", style = MaterialTheme.typography.bodyLarge)
            Settings.HangulToggle.entries.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(hangulToggle = t) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = v.hangulToggle == t, onClick = { app.settings.update { it.copy(hangulToggle = t) } })
                    Text(t.label)
                }
            }
            Text(
                "삼성 키보드가 Shift+Space를 먼저 가로채면 그쪽 한글 입력이 사용됩니다 (그래도 동작합니다).",
                style = MaterialTheme.typography.bodySmall,
            )

            Section("Claude Code")
            Toggle("승인 버튼 표시", "\"Do you want to proceed?\" 같은 선택지를 큰 버튼으로", v.approvalOverlay) { b ->
                app.settings.update { it.copy(approvalOverlay = b) }
            }
            Toggle("터미널 벨 알림", "다른 화면에 있을 때 벨(입력 대기)을 알림으로", v.notifyOnBell) { b ->
                app.settings.update { it.copy(notifyOnBell = b) }
            }
            Toggle("터미널에서 화면 켜짐 유지", null, v.keepScreenOn) { b ->
                app.settings.update { it.copy(keepScreenOn = b) }
            }

            Section("정보")
            TextButton(onClick = onLicenses) { Text("오픈소스 라이선스") }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Toggle(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
