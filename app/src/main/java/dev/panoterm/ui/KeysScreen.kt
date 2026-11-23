package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.R
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.App
import dev.panoterm.data.SshKeyInfo
import kotlinx.coroutines.launch

private class PendingImport(val name: String, val data: ByteArray)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(app: App, onBack: () -> Unit) {
    val keys by app.keys.keys.collectAsState()
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var showGenerate by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var needPassphrase by remember { mutableStateOf<PendingImport?>(null) }
    var confirmDelete by remember { mutableStateOf<SshKeyInfo?>(null) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    fun tryImport(p: PendingImport, passphrase: String) {
        try {
            app.keys.import(p.name, p.data, passphrase)
            needPassphrase = null
            toast(res.getString(R.string.key_imported))
        } catch (e: Exception) {
            if (e.message?.contains("PASSPHRASE_REQUIRED") == true) needPassphrase = p
            else toast(res.getString(R.string.import_failed, e.message ?: e.toString()))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val data = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (data == null) toast(res.getString(R.string.file_unreadable))
            else tryImport(PendingImport(uri.lastPathSegment?.substringAfterLast('/') ?: "imported", data), "")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_keys)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showGenerate = true }) { Text(stringResource(R.string.key_generate)) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.key_from_file)) }
                    OutlinedButton(onClick = { showPaste = true }) { Text(stringResource(R.string.key_paste)) }
                }
            }
            item {
                Text(
                    stringResource(R.string.keys_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(keys, key = { it.id }) { k ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(k.name + if (k.hardware) "  🔒" else "", style = MaterialTheme.typography.titleMedium)
                        Text("${k.type}  ${k.fingerprint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(k.publicKey, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3)
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("public key", k.publicKey)))
                                }
                                toast(res.getString(R.string.pubkey_copied))
                            }) { Text(stringResource(R.string.pubkey_copy)) }
                            TextButton(onClick = {
                                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey)
                                ctx.startActivity(Intent.createChooser(i, res.getString(R.string.pubkey_share)))
                            }) { Text(stringResource(R.string.share)) }
                            TextButton(onClick = { confirmDelete = k }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (showGenerate) {
        var name by remember { mutableStateOf("s25-ultra") }
        var hardware by remember { mutableStateOf(false) }
        var userAuth by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showGenerate = false },
            title = { Text(stringResource(R.string.key_generate_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.key_name_comment)) }, singleLine = true)
                    Row(Modifier.fillMaxWidth().clickable { hardware = false }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = !hardware, onClick = { hardware = false })
                        Column {
                            Text(stringResource(R.string.key_ed25519))
                            Text(stringResource(R.string.key_ed25519_sub), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { hardware = true }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = hardware, onClick = { hardware = true })
                        Column {
                            Text(stringResource(R.string.key_hw))
                            Text(stringResource(R.string.key_hw_sub), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (hardware) {
                        Row(Modifier.fillMaxWidth().clickable { userAuth = !userAuth }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = userAuth, onCheckedChange = { userAuth = it })
                            Column {
                                Text(stringResource(R.string.key_hw_auth))
                                Text(stringResource(R.string.key_hw_auth_sub), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { if (hardware) app.keys.generateHardware(name.trim(), userAuth) else app.keys.generateEd25519(name.trim()) }
                        .onSuccess { toast(res.getString(R.string.key_generated)) }
                        .onFailure { toast(res.getString(R.string.generate_failed, it.message ?: it.toString())) }
                    showGenerate = false
                }) { Text(stringResource(R.string.generate)) }
            },
            dismissButton = { TextButton(onClick = { showGenerate = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showPaste) {
        var name by remember { mutableStateOf("imported") }
        var text by remember { mutableStateOf("") }
        // Secure fields: no copy/cut, password keyboard, nothing learned by the IME.
        val pass = rememberTextFieldState()
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text(stringResource(R.string.key_paste_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    OutlinedTextField(
                        text, { text = it }, label = { Text("-----BEGIN OPENSSH PRIVATE KEY-----...") },
                        modifier = Modifier.heightIn(min = 120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedSecureTextField(pass, label = { Text(stringResource(R.string.key_passphrase_opt)) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPaste = false
                    tryImport(PendingImport(name.trim(), text.trim().toByteArray()), pass.text.toString())
                }) { Text(stringResource(R.string.settings_import)) }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    needPassphrase?.let { p ->
        val pass = remember(p) { androidx.compose.foundation.text.input.TextFieldState() }
        AlertDialog(
            onDismissRequest = { needPassphrase = null },
            title = { Text(stringResource(R.string.key_passphrase_title)) },
            text = {
                OutlinedSecureTextField(pass, label = { Text(stringResource(R.string.password)) })
            },
            confirmButton = { TextButton(onClick = { tryImport(p, pass.text.toString()) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { needPassphrase = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmDelete?.let { k ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.key_delete_title)) },
            text = { Text(stringResource(R.string.key_delete_text, k.name)) },
            confirmButton = {
                TextButton(onClick = { app.keys.delete(k.id); confirmDelete = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
