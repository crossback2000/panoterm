package dev.panoterm.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.R
import dev.panoterm.term.SearchHit
import dev.panoterm.term.TermSession
import dev.panoterm.term.TerminalWebView
import dev.panoterm.term.TmuxSearch
import java.util.concurrent.Executors

/**
 * Scrollback search. Inside tmux the history is tmux's, so tmux's copy-mode search does the work
 * (see [TmuxSearch]); otherwise the page searches xterm's own buffer. Main thread only.
 */
class ScrollbackSearch(private val session: TermSession, private val view: () -> TerminalWebView) {
    /** Matches of the last search; null before the first one. */
    var count by mutableStateOf<Int?>(null)
        private set
    /** Shown match (0 = oldest), -1 when unknown (tmux does not tell). */
    var index by mutableIntStateOf(-1)
        private set
    /** The query [count] belongs to. */
    var searched by mutableStateOf("")
        private set

    private var tmux: String? = null
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    private fun show(q: String, hit: SearchHit) { searched = q; count = hit.count; index = hit.index }

    fun find(query: String) {
        val q = query.replace('\n', ' ').replace('\r', ' ')
        if (q.isEmpty()) return
        val g = ++gen
        stopHighlight()
        val name = session.tmuxName()
        if (name == null) { view().searchFind(q) { if (g == gen) show(q, it) }; return }
        worker.execute {
            val n = TmuxSearch.parseCount(session.execQuiet(TmuxSearch.command(name, TmuxSearch.Op.START, q), 8000))
            main.post {
                if (g != gen) return@post
                // tmux could not be asked (old connection, no tmux after all): search what the page has.
                if (n == null) view().searchFind(q) { if (g == gen) show(q, it) }
                else { tmux = if (n > 0) name else null; show(q, SearchHit(n, -1)) }
            }
        }
    }

    fun step(older: Boolean) {
        val q = searched
        if ((count ?: 0) == 0) return
        val name = tmux
        if (name == null) { val g = gen; view().searchStep(older) { if (g == gen) show(q, it) }; return }
        val op = if (older) TmuxSearch.Op.OLDER else TmuxSearch.Op.NEWER
        worker.execute { session.execQuiet(TmuxSearch.command(name, op, q)) }
    }

    /** Ends the search: tmux leaves copy mode, the page drops its highlight and scrolls to the bottom. */
    fun close() {
        gen++
        stopHighlight()
        count = null; index = -1; searched = ""
    }

    private fun stopHighlight() {
        val name = tmux
        tmux = null
        if (name != null) worker.execute { session.execQuiet(TmuxSearch.command(name, TmuxSearch.Op.END, "")) }
        else if (count != null) view().searchClear()
    }

    private companion object {
        /** One for all sessions; keeps each session's tmux commands in order. */
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search").apply { isDaemon = true } }
    }
}

/** The search row drawn over the bottom of the terminal. Enter = search / older match, Shift+Enter = newer. */
@Composable
fun SearchBar(search: ScrollbackSearch, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun enter(shift: Boolean) {
        if (query.isEmpty()) return
        if (query != search.searched || search.count == null) search.find(query) else search.step(older = !shift)
    }

    Row(
        Modifier.fillMaxWidth().background(Color(0xFF15151A)).height(44.dp).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = Color.Gray, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = { query = it.replace("\n", "") },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { enter(shift = false) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    if (k.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (k.keyCode) {
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { enter(k.isShiftPressed); true }
                        KeyEvent.KEYCODE_ESCAPE -> { onClose(); true }
                        else -> false
                    }
                },
            )
        }
        val n = search.count
        if (n != null && query == search.searched) {
            Text(
                when {
                    n == 0 -> stringResource(R.string.search_none)
                    search.index >= 0 -> "${search.index + 1}/$n"
                    else -> pluralStringResource(R.plurals.count_lines, n, n)
                },
                color = if (n == 0) Color(0xFFFF8A80) else Color.LightGray, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        BarButton("▲", stringResource(R.string.search_older)) { if (query != search.searched || search.count == null) enter(false) else search.step(older = true) }
        BarButton("▼", stringResource(R.string.search_newer)) { if (query != search.searched || search.count == null) enter(false) else search.step(older = false) }
        BarButton("✕", stringResource(R.string.close), onClose)
    }
}

@Composable
private fun BarButton(label: String, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(44.dp).widthIn(min = 40.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
    ) { Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp) }
}
