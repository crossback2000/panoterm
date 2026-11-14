package dev.panoterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.panoterm.App
import dev.panoterm.data.Host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(app: App, hostId: String?, prefill: Host?, onDone: () -> Unit) {
    val base = remember(hostId) { hostId?.let { app.hosts.get(it) } ?: prefill ?: Host() }
    val keys by app.keys.keys.collectAsState()

    var name by remember { mutableStateOf(base.name) }
    var address by remember { mutableStateOf(base.address) }
    var port by remember { mutableStateOf(base.port.toString()) }
    var user by remember { mutableStateOf(base.user) }
    var keyId by remember { mutableStateOf(base.keyId ?: keys.firstOrNull()?.id) }
    var useTailnet by remember { mutableStateOf(base.useTailnet) }
    var tmux by remember { mutableStateOf(base.tmux) }
    var tmuxSession by remember { mutableStateOf(base.tmuxSession) }
    var fontSize by remember { mutableStateOf(base.fontSize.toString()) }

    val valid = address.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 && keyId != null

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (hostId == null) "호스트 추가" else "호스트 편집") },
                navigationIcon = { TextButton(onClick = onDone) { Text("←") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("이름 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("주소 (MagicDNS 이름 또는 IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                OutlinedTextField(
                    user, { user = it }, label = { Text("사용자") }, singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, label = { Text("포트") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Text("SSH 키", style = MaterialTheme.typography.titleSmall)
            if (keys.isEmpty()) {
                Text("키가 없습니다. 호스트 목록 > SSH 키에서 먼저 생성하거나 가져오세요.", color = MaterialTheme.colorScheme.error)
            }
            keys.forEach { k ->
                Row(Modifier.fillMaxWidth().clickable { keyId = k.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = keyId == k.id, onClick = { keyId = k.id })
                    Column {
                        Text(k.name)
                        Text("${k.type} ${k.fingerprint.take(24)}…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SwitchRow("Tailscale(tailnet)로 연결", useTailnet) { useTailnet = it }
            SwitchRow("접속 시 tmux 자동 연결", tmux) { tmux = it }
            if (tmux) {
                OutlinedTextField(tmuxSession, { tmuxSession = it }, label = { Text("tmux 세션 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                fontSize, { fontSize = it.filter(Char::isDigit) }, label = { Text("글자 크기 (8–32)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = valid,
                    onClick = {
                        app.hosts.upsert(
                            base.copy(
                                name = name.trim(),
                                address = address.trim(),
                                port = port.toIntOrNull() ?: 22,
                                user = user.trim(),
                                keyId = keyId,
                                useTailnet = useTailnet,
                                tmux = tmux,
                                tmuxSession = tmuxSession.trim().ifBlank { "main" },
                                fontSize = (fontSize.toIntOrNull() ?: 14).coerceIn(8, 32),
                            )
                        )
                        onDone()
                    },
                ) { Text("저장") }
                if (hostId != null) {
                    TextButton(onClick = { app.hosts.delete(hostId); onDone() }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
