package dev.panoterm.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.panoterm.App
import dev.panoterm.net.UpdateChecker.State

/** Update status card on the host list. Hidden when there is nothing to show. */
@Composable
fun UpdateCard(app: App) {
    val ctx = LocalContext.current
    val s by app.updates.state.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    if (s is State.Idle) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            when (val st = s) {
                is State.Available -> {
                    Text("새 버전 ${st.u.version}이 나왔어요")
                    Row {
                        TextButton(onClick = { app.updates.download() }) { Text("받기") }
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.u.url))) }) { Text("변경 내용") }
                        TextButton(onClick = { app.updates.dismiss() }) { Text("나중에") }
                    }
                }
                is State.Downloading -> {
                    Text("${st.u.version} 받는 중… ${st.percent}%")
                    LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                is State.Ready -> {
                    Text("${st.u.version} 설치 준비됐어요")
                    if (sessions.isNotEmpty()) {
                        Text("설치하면 앱이 다시 시작돼서 열린 세션이 끊겨요. 세션을 닫으면 자동으로 설치됩니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { app.updates.install() }) { Text("지금 설치") }
                        TextButton(onClick = { app.updates.dismiss() }) { Text("나중에") }
                    }
                }
                is State.NeedsPermission -> {
                    Text("업데이트를 설치하려면 'PanoTerm에서 앱 설치 허용'을 한 번 켜주세요.", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                            )
                        }) { Text("허용하러 가기") }
                        TextButton(onClick = { app.updates.install() }) { Text("다시 설치") }
                    }
                }
                is State.Installing -> {
                    Text("${st.u.version} 설치 중…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                is State.Failed -> {
                    Text(st.message, color = MaterialTheme.colorScheme.error)
                    Row {
                        if (st.u != null) TextButton(onClick = { app.updates.download() }) { Text("다시 받기") }
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dev.panoterm.net.UpdateChecker.RELEASES))) }) { Text("직접 받기") }
                        TextButton(onClick = { app.updates.dismiss() }) { Text("닫기") }
                    }
                }
                State.Idle -> {}
            }
        }
    }
}
