package dev.panoterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.panoterm.term.HostKeyPrompt

@Composable
fun HostKeyDialog(p: HostKeyPrompt) {
    AlertDialog(
        onDismissRequest = { p.result.complete(false) },
        title = { Text(if (p.changed) "⚠ 호스트 키가 변경되었습니다" else "새 호스트 키 확인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.hostPort)
                Text(p.keyType, style = MaterialTheme.typography.bodySmall)
                Text(p.fingerprint, fontFamily = FontFamily.Monospace)
                if (p.changed) {
                    Text(
                        "저장된 키와 다릅니다. 중간자 공격일 수 있습니다. 서버를 다시 설치했거나 키를 직접 바꾼 경우에만 수락하세요.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("서버에서 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 결과와 같은지 확인하세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { p.result.complete(true) }) { Text(if (p.changed) "교체하고 연결" else "신뢰하고 연결") }
        },
        dismissButton = { TextButton(onClick = { p.result.complete(false) }) { Text("취소") } },
    )
}
