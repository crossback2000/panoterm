package dev.panoterm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.panoterm.App

/** An action of the terminal's top bar: shown as [icon] there, as "[icon]  [label]" in the ⋮ menu. */
data class TopAction(val id: String, val icon: String, val label: String)

/** Every action that can be placed in the top bar (order here = order in the editor). */
val ALL_TOP_ACTIONS: List<TopAction> = listOf(
    TopAction("width", "↔", "넓게 보기 전환"),
    TopAction("lens", "▦", "표 크게 보기"),
    TopAction("reader", "📖", "읽기 모드"),
    TopAction("palette", "⚡", "명령 팔레트"),
    TopAction("image", "🖼", "이미지 보내기"),
    TopAction("files", "📁", "파일 탐색기"),
    TopAction("ports", "⇄", "포트 미리보기"),
    TopAction("paste", "📋", "붙여넣기"),
    TopAction("fontUp", "A+", "글자 크게"),
    TopAction("fontDown", "A−", "글자 작게"),
    TopAction("tmux", "⊞", "tmux 키 줄 보이기/숨기기"),
    TopAction("keyboard", "⌨", "키보드 열기"),
    TopAction("close", "✕", "연결 끊고 닫기"),
)

val DEFAULT_TOP_IDS = listOf("width", "lens", "files", "palette")

fun topActionsFor(ids: List<String>): List<TopAction> {
    val byId = ALL_TOP_ACTIONS.associateBy { it.id }
    return ids.ifEmpty { DEFAULT_TOP_IDS }.distinct().mapNotNull { byId[it] }
}

/** Choose and order the top bar icons. Whatever isn't shown stays in the ⋮ menu. */
@Composable
fun TopBarEditorDialog(app: App, onDismiss: () -> Unit) {
    val v by app.settings.values.collectAsState()
    val enabled = topActionsFor(v.topBar).map { it.id }
    val disabled = ALL_TOP_ACTIONS.map { it.id }.filter { it !in enabled }
    val byId = ALL_TOP_ACTIONS.associateBy { it.id }
    fun save(list: List<String>) = app.settings.update { it.copy(topBar = list) }
    fun name(id: String) = byId[id]?.let { "${it.icon}  ${it.label}" } ?: id

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("상단 아이콘 편집") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Text(
                        "체크한 아이콘이 위 막대에 순서대로 나와요. 나머지는 ⋮ 메뉴에 있어요. " +
                            "화면이 좁으면 4~5개가 알맞아요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                itemsIndexed(enabled, key = { _, id -> "on-$id" }) { i, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Saving an empty list would mean "default"; keep at least one icon.
                        Checkbox(checked = true, enabled = enabled.size > 1, onCheckedChange = { save(enabled - id) })
                        Text(name(id), Modifier.weight(1f))
                        TextButton(enabled = i > 0, onClick = {
                            val l = enabled.toMutableList(); l.add(i - 1, l.removeAt(i)); save(l)
                        }) { Text("▲") }
                        TextButton(enabled = i < enabled.size - 1, onClick = {
                            val l = enabled.toMutableList(); l.add(i + 1, l.removeAt(i)); save(l)
                        }) { Text("▼") }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("⋮ 메뉴에만", style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(disabled, key = { _, id -> "off-$id" }) { _, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = false, onCheckedChange = { save(enabled + id) })
                        Text(name(id))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
        dismissButton = { TextButton(onClick = { save(emptyList()) }) { Text("기본값") } },
    )
}
