package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.R
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
                    Text(stringResource(R.string.upd_available, st.u.version))
                    Row {
                        TextButton(onClick = { app.updates.download() }) { Text(stringResource(R.string.upd_get)) }
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.u.url))) }) { Text(stringResource(R.string.upd_changes)) }
                        TextButton(onClick = { app.updates.dismiss() }) { Text(stringResource(R.string.upd_later)) }
                    }
                }
                is State.Downloading -> {
                    Text(stringResource(R.string.upd_downloading, st.u.version, st.percent))
                    LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                is State.Ready -> {
                    Text(stringResource(R.string.upd_ready, st.u.version))
                    if (sessions.isNotEmpty()) {
                        Text(stringResource(R.string.upd_ready_note), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { app.updates.install() }) { Text(stringResource(R.string.upd_install_now)) }
                        TextButton(onClick = { app.updates.dismiss() }) { Text(stringResource(R.string.upd_later)) }
                    }
                }
                is State.NeedsPermission -> {
                    Text(stringResource(R.string.upd_need_perm), style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                            )
                        }) { Text(stringResource(R.string.upd_go_allow)) }
                        TextButton(onClick = { app.updates.install() }) { Text(stringResource(R.string.upd_retry_install)) }
                    }
                }
                is State.Installing -> {
                    Text(stringResource(R.string.upd_installing, st.u.version))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                is State.Failed -> {
                    Text(st.message, color = MaterialTheme.colorScheme.error)
                    Row {
                        if (st.u != null) TextButton(onClick = { app.updates.download() }) { Text(stringResource(R.string.upd_redownload)) }
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dev.panoterm.net.UpdateChecker.RELEASES))) }) { Text(stringResource(R.string.upd_manual)) }
                        TextButton(onClick = { app.updates.dismiss() }) { Text(stringResource(R.string.close)) }
                    }
                }
                State.Idle -> {}
            }
        }
    }
}
