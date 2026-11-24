package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.str
import dev.panoterm.R
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
val ALL_TOP_ACTIONS: List<TopAction> get() = listOf(
    TopAction("width", "↔", str(R.string.top_width)),
    TopAction("lens", "▦", str(R.string.top_lens)),
    TopAction("reader", "📖", str(R.string.top_reader)),
    TopAction("search", "🔍", str(R.string.top_search)),
    TopAction("palette", "⚡", str(R.string.top_palette)),
    TopAction("image", "🖼", str(R.string.top_image)),
    TopAction("files", "📁", str(R.string.top_files)),
    TopAction("ports", "⇄", str(R.string.top_ports)),
    TopAction("paste", "📋", str(R.string.key_paste)),
    TopAction("fontUp", "A+", str(R.string.top_fontUp)),
    TopAction("fontDown", "A−", str(R.string.top_fontDown)),
    TopAction("tmux", "⊞", str(R.string.top_tmux)),
    TopAction("keyboard", "⌨", str(R.string.top_keyboard)),
    TopAction("close", "✕", str(R.string.top_close)),
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
        title = { Text(stringResource(R.string.topbar_edit)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Text(
                        stringResource(R.string.topbar_edit_desc),
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
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text(stringResource(R.string.topbar_menu_only), style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(disabled, key = { _, id -> "off-$id" }) { _, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = false, onCheckedChange = { save(enabled + id) })
                        Text(name(id))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { save(emptyList()) }) { Text(stringResource(R.string.defaults)) } },
    )
}
