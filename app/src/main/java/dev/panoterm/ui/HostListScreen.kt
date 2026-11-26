package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.str
import dev.panoterm.R
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.App
import dev.panoterm.data.Host
import dev.panoterm.net.TailscaleManager
import dev.panoterm.term.TermSession
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    app: App,
    onOpenSession: (TermSession) -> Unit,
    onConnectHost: (Host) -> Unit,
    onEdit: (id: String?, prefill: Host?) -> Unit,
    onKeys: () -> Unit,
    onLicenses: () -> Unit,
) {
    val update by app.updates.state.collectAsState()
    val hosts by app.hosts.hosts.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    val tsState by app.tailscale.state.collectAsState()
    val authUrl by app.tailscale.authUrl.collectAsState()
    val peers by app.tailscale.peers.collectAsState()
    val lastLog by app.tailscale.lastLog.collectAsState()
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("PanoTerm") },
                actions = {
                    TextButton(onClick = onKeys) { Text(stringResource(R.string.ssh_keys)) }
                    TextButton(onClick = onLicenses) { Text(stringResource(R.string.settings_title)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null, null) }) { Text("+", fontSize = 26.sp) }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (update !is dev.panoterm.net.UpdateChecker.State.Idle) {
                item { UpdateCard(app) }
            }
            item {
                TailscaleCard(
                    state = tsState,
                    authUrl = authUrl,
                    lastLog = lastLog,
                    onLogin = { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                    onRefresh = { app.tailscale.refreshPeers() },
                    onLogout = { app.tailscale.logout() },
                )
            }

            if (sessions.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.hosts_open_sessions)) }
                items(sessions, key = { it.id }) { s -> SessionRow(s) { onOpenSession(s) } }
            }

            item { SectionTitle(stringResource(R.string.hosts_saved)) }
            if (hosts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.hosts_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(hosts, key = { it.id }) { h ->
                HostRow(
                    h,
                    onConnect = {
                        if (h.keyId == null || app.keys.get(h.keyId) == null) {
                            Toast.makeText(ctx, res.getString(R.string.hosts_need_key), Toast.LENGTH_SHORT).show()
                            onEdit(h.id, null)
                        } else {
                            onConnectHost(h)
                        }
                    },
                    onEdit = { onEdit(h.id, null) },
                )
            }

            if (peers.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.hosts_tailnet)) }
                items(peers, key = { "peer-${it.dns}-${it.ip}" }) { p ->
                    PeerRow(p) { onEdit(null, Host(name = p.name, address = p.dns.ifBlank { p.ip })) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TailscaleCard(
    state: String,
    authUrl: String?,
    lastLog: String,
    onLogin: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (state == "Running") Color(0xFF4CAF50) else Color(0xFFFFA000))
                Spacer(Modifier.width(8.dp))
                Text("Tailscale: ${stateLabel(state)}", style = MaterialTheme.typography.titleMedium)
            }
            if (authUrl != null) {
                Spacer(Modifier.size(8.dp))
                Button(onClick = { onLogin(authUrl) }) { Text(stringResource(R.string.ts_login)) }
            }
            if (state == "Running") {
                Row {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.ts_refresh)) }
                    TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
                }
            }
            if (state == "Error" && lastLog.isNotBlank()) {
                Text(lastLog, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun stateLabel(s: String) = when (s) {
    "Running" -> str(R.string.state_connected)
    "NeedsLogin" -> str(R.string.ts_needs_login)
    "NeedsMachineAuth" -> str(R.string.ts_needs_auth)
    "Starting" -> str(R.string.ts_starting)
    "Stopped" -> str(R.string.ts_stopped)
    "Error" -> str(R.string.ts_error)
    else -> s
}

@Composable
private fun SessionRow(s: TermSession, onClick: () -> Unit) {
    val st by s.state.collectAsState()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (st == TermSession.State.CONNECTED) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
            Spacer(Modifier.width(8.dp))
            Text(s.host.title + (s.label?.let { " · $it" } ?: ""), Modifier.weight(1f))
            Text(
                when (st) {
                    TermSession.State.CONNECTED -> stringResource(R.string.state_connected)
                    TermSession.State.CONNECTING, TermSession.State.IDLE -> stringResource(R.string.state_connecting)
                    TermSession.State.CLOSED -> stringResource(R.string.state_closed)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HostRow(h: Host, onConnect: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onConnect)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${h.user}@${h.address}:${h.port}")
                        if (h.useTailnet) append(" · tailnet")
                        if (h.tmux) append(" · tmux(${h.tmuxSession})")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.edit)) }
        }
    }
}

@Composable
private fun PeerRow(p: TailscaleManager.Peer, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (p.online) Color(0xFF4CAF50) else Color(0xFF616161))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name)
            Text("${p.ip} · ${p.os}", style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.add), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Dot(c: Color) {
    androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}
