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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.panoterm.App
import dev.panoterm.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: App,
    biometricAvailable: Boolean,
    onLicenses: () -> Unit,
    onKeyBar: () -> Unit,
    onSnippets: () -> Unit,
    onBack: () -> Unit,
) {
    val v by app.settings.values.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var backupMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) } // "export" | "import"
    var pendingUri by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null) }
    var topBarEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (topBarEdit) TopBarEditorDialog(app) { topBarEdit = false }

    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) { pendingUri = uri; backupMode = "export" } }
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) { pendingUri = uri; backupMode = "import" } }

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
            Toggle(
                "접속할 때 생체인증", "새 SSH 세션을 열 때마다 지문/얼굴 확인 (키 사용 보호)",
                v.keyAuth && biometricAvailable, enabled = biometricAvailable,
            ) { b -> app.settings.update { it.copy(keyAuth = b) } }

            Section("백업")
            Text(
                "호스트, SSH 키, 서버 지문, 명령 목록을 암호로 잠근 파일 하나로 저장합니다. 폰을 바꿀 때 쓰세요. " +
                    "하드웨어(StrongBox) 키는 칩 밖으로 나올 수 없어서 빠집니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { exportPicker.launch("panoterm-backup.ptbak") }) { Text("내보내기") }
                androidx.compose.material3.OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("가져오기") }
            }

            Section("키보드 (K380 등)")
            TextButton(onClick = onKeyBar) { Text("화면 키 줄 편집 →") }
            TextButton(onClick = { topBarEdit = true }) { Text("터미널 상단 아이콘 편집 →") }
            TextButton(onClick = onSnippets) { Text("명령(⚡) 편집 →") }
            Toggle("CapsLock을 Ctrl로", "CapsLock을 누르고 있는 동안 Ctrl로 동작", v.capsAsCtrl) { b ->
                app.settings.update { it.copy(capsAsCtrl = b) }
            }
            Toggle("볼륨 아래 키 = Ctrl", "터미널에서 볼륨 아래 키를 누르면 다음 글자에 Ctrl 적용", v.volumeAsCtrl) { b ->
                app.settings.update { it.copy(volumeAsCtrl = b) }
            }
            Toggle("두 손가락 왼쪽 밀기 = Esc", "Claude 작업 중단할 때 편해요", v.twoFingerEsc) { b ->
                app.settings.update { it.copy(twoFingerEsc = b) }
            }
            Toggle(
                "앱에서 한글 조합 (하드웨어 키보드)",
                "보통은 끄세요: 휴대폰 키보드가 K380 한글도 조합해 줘요(삼성 키보드는 Shift+Space로 한/영). 휴대폰 키보드가 하드웨어 키보드 한글을 못 칠 때만 켜세요.",
                v.appHangul,
            ) { b -> app.settings.update { it.copy(appHangul = b) } }
            if (v.appHangul) {
                Text("앱 한/영 전환 키", style = MaterialTheme.typography.bodyLarge)
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
                    "휴대폰 키보드도 같은 키로 한/영을 바꾸면 둘이 엇갈려요. 휴대폰 키보드의 하드웨어 키보드 한/영 키와 다른 키를 고르세요. 앱이 한글일 때 상단에 '한'이 보여요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Claude Code")
            Toggle("입력 자동완성", "/ 누르면 Claude 명령, cd ser 하면 service/ 같은 후보를 말풍선으로 보여줘요", v.autocomplete) { b ->
                app.settings.update { it.copy(autocomplete = b) }
            }
            Toggle("작업 상태 탭", "세션·tmux 창마다 작업 중(파랑)/승인 대기(주황)를 위쪽 탭에 표시, 탭하면 바로 전환", v.agentTabs) { b ->
                app.settings.update { it.copy(agentTabs = b) }
            }
            Toggle("승인 버튼 표시", "\"Do you want to proceed?\" 같은 선택지를 큰 버튼으로", v.approvalOverlay) { b ->
                app.settings.update { it.copy(approvalOverlay = b) }
            }
            Toggle("터미널 벨 알림", "다른 화면에 있을 때 벨(입력 대기)을 알림으로", v.notifyOnBell) { b ->
                app.settings.update { it.copy(notifyOnBell = b) }
            }
            Toggle("서버에서 클립보드 복사", "서버(tmux 등)에서 복사한 내용을 폰 클립보드로 받기. 켜면 화면에 출력된 내용이 클립보드를 바꿀 수 있어요", v.remoteClipboard) { b ->
                app.settings.update { it.copy(remoteClipboard = b) }
            }
            Toggle("터미널에서 화면 켜짐 유지", null, v.keepScreenOn) { b ->
                app.settings.update { it.copy(keepScreenOn = b) }
            }
            Text("화면 그리기 방식 (터미널을 다시 열면 적용)", style = MaterialTheme.typography.bodyLarge)
            listOf(
                "auto" to "자동 (추천) — GPU가 있으면 WebGL, 아니면 DOM",
                "webgl" to "WebGL — 가장 빠름",
                "dom" to "DOM — 느리지만 가장 호환성이 좋아요",
            ).forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(renderer = id) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = v.renderer == id, onClick = { app.settings.update { it.copy(renderer = id) } })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section("업데이트")
            Text("현재 버전 ${dev.panoterm.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            UpdateCard(app)
            Toggle("새 버전 확인", "앱을 열 때 GitHub 릴리스를 확인 (6시간에 한 번, 보내는 정보 없음)", v.updateCheck) { b ->
                app.settings.update { it.copy(updateCheck = b) }
            }
            Toggle(
                "자동 업데이트", "새 버전을 알아서 받아서 설치. 열린 세션이 있으면 다 닫은 뒤에 설치해요.",
                v.autoUpdate && v.updateCheck, enabled = v.updateCheck,
            ) { b -> app.settings.update { it.copy(autoUpdate = b) } }
            Toggle(
                "Wi-Fi에서만 받기", "데이터 요금 아끼기 (APK가 40MB 정도)",
                v.updateWifiOnly, enabled = v.autoUpdate && v.updateCheck,
            ) { b -> app.settings.update { it.copy(updateWifiOnly = b) } }
            Text(
                "처음 한 번은 'PanoTerm에서 앱 설치 허용'을 켜야 하고, 설치 확인 창이 뜰 수도 있어요. 그다음부터는 확인 없이 설치됩니다. " +
                    "받은 파일은 지금 앱과 같은 서명인지 확인한 뒤에만 설치해요.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                app.updates.check(force = true)
                android.widget.Toast.makeText(ctx, "확인 중…", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("지금 확인") }

            Section("정보")
            TextButton(onClick = onLicenses) { Text("오픈소스 라이선스") }
        }
    }

    val mode = backupMode
    val uri = pendingUri
    if (mode != null && uri != null) {
        BackupPasswordDialog(
            export = mode == "export",
            onDismiss = { backupMode = null; pendingUri = null },
            onConfirm = { pw ->
                backupMode = null; pendingUri = null
                val msg = runCatching {
                    if (mode == "export") {
                        val (blob, s) = dev.panoterm.data.Backup.export(app.hosts, app.keys, app.knownHosts.path, app.snippets, pw)
                        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(blob) }
                        "백업 완료: 호스트 ${s.hosts}개, 키 ${s.keys}개" + if (s.skippedHardwareKeys > 0) " (하드웨어 키 ${s.skippedHardwareKeys}개 제외)" else ""
                    } else {
                        val blob = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        val s = dev.panoterm.data.Backup.import(blob, pw, app.hosts, app.keys, app.knownHosts, app.snippets)
                        "가져오기 완료: 호스트 ${s.hosts}개, 키 ${s.keys}개"
                    }
                }.getOrElse { "실패: ${it.message}" }
                pw.fill(' ')
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            },
        )
    }
}

@Composable
private fun BackupPasswordDialog(export: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    // Secure fields: no copy/cut, password keyboard, nothing learned by the IME.
    val p1 = androidx.compose.foundation.text.input.rememberTextFieldState()
    val p2 = androidx.compose.foundation.text.input.rememberTextFieldState()
    val ok = if (export) p1.text.length >= 8 && p1.text.contentEquals(p2.text) else p1.text.isNotEmpty()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (export) "백업 암호 정하기" else "백업 암호 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export) Text("이 암호를 잊으면 백업을 열 수 없어요. 8자 이상.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedSecureTextField(p1, label = { Text("암호") })
                if (export) androidx.compose.material3.OutlinedSecureTextField(p2, label = { Text("암호 확인") })
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                // Straight into a CharArray (no immutable String copy); the caller wipes it.
                val t = p1.text
                onConfirm(CharArray(t.length) { t[it] })
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
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
