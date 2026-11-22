package dev.panoterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.panoterm.App
import dev.panoterm.data.Snippet

/** Choose and order the keys of the bottom key row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBarEditorScreen(app: App, onBack: () -> Unit) {
    val v by app.settings.values.collectAsState()
    val current = v.keyBar.ifEmpty { DEFAULT_KEY_IDS }
    val enabled = current.distinct().filter { id -> ALL_KEYS.any { it.id == id } }
    val disabled = ALL_KEYS.map { it.id }.filter { it !in enabled }
    val byId = ALL_KEYS.associateBy { it.id }

    fun save(list: List<String>) = app.settings.update { it.copy(keyBar = list) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("화면 키 줄 편집") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { TextButton(onClick = { save(emptyList()) }) { Text("기본값") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
            item {
                Text(
                    "체크한 키가 순서대로 나옵니다. ⏎ Enter는 항상 오른쪽 끝에 고정이에요. " +
                        "◀ ▶는 손가락으로 끌면 커서가 움직이는 키입니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(enabled, key = { _, id -> "on-$id" }) { i, id ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = { save(enabled - id) })
                    KeyName(byId[id]?.label ?: id, KEY_HELP[id], Modifier.weight(1f))
                    TextButton(enabled = i > 0, onClick = {
                        val l = enabled.toMutableList(); l.add(i - 1, l.removeAt(i)); save(l)
                    }) { Text("▲") }
                    TextButton(enabled = i < enabled.size - 1, onClick = {
                        val l = enabled.toMutableList(); l.add(i + 1, l.removeAt(i)); save(l)
                    }) { Text("▼") }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("숨긴 키", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(disabled, key = { _, id -> "off-$id" }) { _, id ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = { save(enabled + id) })
                    KeyName(byId[id]?.label ?: id, KEY_HELP[id], Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KeyName(label: String, help: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label)
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Add / remove / reorder command palette entries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsEditorScreen(app: App, onBack: () -> Unit) {
    val items by app.snippets.items.collectAsState()
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("명령 편집") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { TextButton(onClick = { editing = true }) { Text("+ 추가") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
            item {
                Text(
                    "터미널의 ⚡ 버튼이나 Ctrl+Shift+P로 엽니다. 자주 쓰는 slash 명령이나 셸 명령을 넣어두세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(items) { i, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label)
                        Text(s.text + if (s.submit) "  ⏎" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    TextButton(enabled = i > 0, onClick = { app.snippets.move(i, -1) }) { Text("▲") }
                    TextButton(enabled = i < items.size - 1, onClick = { app.snippets.move(i, 1) }) { Text("▼") }
                    TextButton(onClick = { app.snippets.remove(i) }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (editing) {
        var label by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var submit by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("명령 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("이름 (비우면 내용 그대로)") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text("보낼 내용") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("보낸 뒤 Enter", Modifier.weight(1f))
                        Switch(checked = submit, onCheckedChange = { submit = it })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    app.snippets.add(Snippet(label.ifBlank { text.lines().first().take(30) }, text, submit))
                    editing = false
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("취소") } },
        )
    }
}
