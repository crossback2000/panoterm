package dev.panoterm.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.App
import dev.panoterm.data.SshKeyInfo

private class PendingImport(val name: String, val data: ByteArray)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(app: App, onBack: () -> Unit) {
    val keys by app.keys.keys.collectAsState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showGenerate by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var needPassphrase by remember { mutableStateOf<PendingImport?>(null) }
    var confirmDelete by remember { mutableStateOf<SshKeyInfo?>(null) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    fun tryImport(p: PendingImport, passphrase: String) {
        try {
            app.keys.import(p.name, p.data, passphrase)
            needPassphrase = null
            toast("키를 가져왔습니다")
        } catch (e: Exception) {
            if (e.message?.contains("PASSPHRASE_REQUIRED") == true) needPassphrase = p
            else toast("가져오기 실패: ${e.message}")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val data = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (data == null) toast("파일을 읽을 수 없습니다")
            else tryImport(PendingImport(uri.lastPathSegment?.substringAfterLast('/') ?: "imported", data), "")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("SSH 키") },
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
                    Button(onClick = { showGenerate = true }) { Text("키 만들기") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("파일") }
                    OutlinedButton(onClick = { showPaste = true }) { Text("붙여넣기") }
                }
            }
            item {
                Text(
                    "개인키는 Android Keystore(StrongBox) 키로 암호화되어 이 기기에만 저장됩니다. 공개키를 서버의 ~/.ssh/authorized_keys 에 추가하세요.",
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
                                clipboard.setText(AnnotatedString(k.publicKey))
                                toast("공개키를 복사했습니다")
                            }) { Text("공개키 복사") }
                            TextButton(onClick = {
                                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey)
                                ctx.startActivity(Intent.createChooser(i, "공개키 공유"))
                            }) { Text("공유") }
                            TextButton(onClick = { confirmDelete = k }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (showGenerate) {
        var name by remember { mutableStateOf("s25-ultra") }
        var hardware by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showGenerate = false },
            title = { Text("SSH 키 만들기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("이름 (공개키 주석)") }, singleLine = true)
                    Row(Modifier.fillMaxWidth().clickable { hardware = false }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = !hardware, onClick = { hardware = false })
                        Column {
                            Text("Ed25519 (추천)")
                            Text("백업 파일로 옮길 수 있어요", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { hardware = true }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = hardware, onClick = { hardware = true })
                        Column {
                            Text("하드웨어 키 (StrongBox, ECDSA)")
                            Text("키가 보안 칩 밖으로 절대 안 나와요. 대신 백업이 안 되고 폰을 바꾸면 새로 만들어야 해요.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { if (hardware) app.keys.generateHardware(name.trim()) else app.keys.generateEd25519(name.trim()) }
                        .onSuccess { toast("생성했습니다. 공개키를 서버에 등록하세요.") }
                        .onFailure { toast("생성 실패: ${it.message}") }
                    showGenerate = false
                }) { Text("생성") }
            },
            dismissButton = { TextButton(onClick = { showGenerate = false }) { Text("취소") } },
        )
    }

    if (showPaste) {
        var name by remember { mutableStateOf("imported") }
        var text by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("개인키 붙여넣기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true)
                    OutlinedTextField(
                        text, { text = it }, label = { Text("-----BEGIN OPENSSH PRIVATE KEY-----...") },
                        modifier = Modifier.heightIn(min = 120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("암호 (있으면)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPaste = false
                    tryImport(PendingImport(name.trim(), text.trim().toByteArray()), pass)
                }) { Text("가져오기") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("취소") } },
        )
    }

    needPassphrase?.let { p ->
        var pass by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { needPassphrase = null },
            title = { Text("키 암호 입력") },
            text = {
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("암호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = { TextButton(onClick = { tryImport(p, pass) }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { needPassphrase = null }) { Text("취소") } },
        )
    }

    confirmDelete?.let { k ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("키 삭제") },
            text = { Text("'${k.name}' 키를 삭제합니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { app.keys.delete(k.id); confirmDelete = null }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}
