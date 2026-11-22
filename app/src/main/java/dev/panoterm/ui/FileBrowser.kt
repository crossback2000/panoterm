package dev.panoterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.panoterm.term.Completion
import dev.panoterm.term.Syntax
import dev.panoterm.term.TermSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private val Bg = Color(0xFF101014)
private val Panel = Color(0xFF1B1B22)
private val Dim = Color(0xFF8A8A96)
private val Fg = Color(0xFFE0E0E0)

/** Largest file the viewer opens / the editor edits. */
private const val VIEW_MAX = 2L shl 20
private const val EDIT_MAX = 512L shl 10
/** Highlighting while editing re-runs on each keystroke: only for smaller files. */
private const val EDIT_HIGHLIGHT_MAX = 120_000

/** Last folder per session, so reopening the browser returns there. */
private val lastDir = HashMap<String, String>()

private data class Entry(val name: String, val dir: Boolean, val link: Boolean, val size: Long, val mtime: Long)

private class OpenFile(
    val path: String,
    val text: String,
    val modTime: Long,
    val size: Long,
    val crlf: Boolean,
    /** Valid UTF-8 and small enough: may be edited without corrupting it. */
    val editable: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
}

private fun tokColor(t: Syntax.Tok): SpanStyle = when (t) {
    Syntax.Tok.KEYWORD -> SpanStyle(color = Color(0xFFC586C0))
    Syntax.Tok.CONSTANT -> SpanStyle(color = Color(0xFF569CD6))
    Syntax.Tok.STRING, Syntax.Tok.VALUE -> SpanStyle(color = Color(0xFFCE9178))
    Syntax.Tok.COMMENT -> SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)
    Syntax.Tok.NUMBER -> SpanStyle(color = Color(0xFFB5CEA8))
    Syntax.Tok.TYPE -> SpanStyle(color = Color(0xFF4EC9B0))
    Syntax.Tok.FUNCTION -> SpanStyle(color = Color(0xFFDCDCAA))
    Syntax.Tok.KEY, Syntax.Tok.VARIABLE -> SpanStyle(color = Color(0xFF9CDCFE))
    Syntax.Tok.HEADING -> SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)
    Syntax.Tok.PUNCT -> SpanStyle(color = Color(0xFF808080))
    Syntax.Tok.TAG -> SpanStyle(color = Color(0xFF569CD6))
}

private fun styled(text: String, spans: List<Syntax.Span>, find: String = ""): AnnotatedString = buildAnnotatedString {
    append(text)
    for (s in spans) {
        val e = s.end.coerceAtMost(text.length)
        if (s.start < e) addStyle(tokColor(s.tok), s.start, e)
    }
    if (find.isNotEmpty()) {
        var i = text.indexOf(find, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(background = Color(0x80FFB300)), i, i + find.length)
            i = text.indexOf(find, i + find.length, ignoreCase = true)
        }
    }
}

/** Hides .env values (same length, so highlighting offsets still match). */
private fun maskEnv(line: String): String {
    val r = Syntax.envValue(line) ?: return line
    return line.substring(0, r.first) + "•".repeat(r.last - r.first + 1) + line.substring(r.last + 1)
}

private fun human(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> String.format("%.1f MB", size / 1048576.0)
}

private fun ago(sec: Long): String {
    val d = System.currentTimeMillis() / 1000 - sec
    return when {
        d < 60 -> "방금"
        d < 3600 -> "${d / 60}분 전"
        d < 86400 -> "${d / 3600}시간 전"
        d < 86400 * 30 -> "${d / 86400}일 전"
        else -> java.text.SimpleDateFormat("yy.MM.dd", java.util.Locale.KOREA).format(java.util.Date(sec * 1000))
    }
}

private fun parent(p: String): String = when {
    p == "/" || p.isEmpty() -> "/"
    else -> p.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
}

private fun child(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

/** Bytes → text for the viewer. null = binary. */
private fun decode(bytes: ByteArray): Pair<String, Boolean>? {
    if (bytes.take(8000).any { it == 0.toByte() }) return null
    val strict = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() to true }
        .getOrElse { String(bytes, Charsets.UTF_8) to false }
}

private fun copyText(ctx: android.content.Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", text))
    Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
}

/** Shell-safe path for typing at the prompt (quoted only when needed). */
private fun shellPath(p: String) = if (p.all { it.isLetterOrDigit() || it in "/._-~+@:," }) p else Completion.shq(p)

/**
 * SFTP file browser over the session's SSH connection, with a syntax-highlighted viewer that
 * wraps lines to the phone's width and a simple editor. Starts in the folder the terminal
 * (tmux pane) is in, so the files Claude just touched are right there.
 */
@Composable
fun FileBrowser(session: TermSession, fontSize: Int, onInsertPath: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(lastDir[session.id]) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    var pathEdit by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<OpenFile?>(null) }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    fun load(path: String) {
        loading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val o = JSONObject(session.listDir(path))
                    val arr = o.getJSONArray("entries")
                    o.getString("path") to (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        Entry(e.getString("name"), e.optBoolean("dir"), e.optBoolean("link"), e.optLong("size"), e.optLong("mtime"))
                    }
                }
            }
            loading = false
            r.onSuccess { (abs, list) ->
                dir = abs
                lastDir[session.id] = abs
                entries = list
                filter = ""
            }.onFailure { error = it.message ?: it.toString() }
        }
    }

    fun openFile(path: String) {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val fd = session.readFile(path, VIEW_MAX)
                    val dec = decode(fd.data) ?: throw IllegalStateException("바이너리 파일이라 열 수 없어요")
                    val crlf = dec.first.contains("\r\n")
                    OpenFile(
                        path = fd.path,
                        text = if (crlf) dec.first.replace("\r\n", "\n") else dec.first,
                        modTime = fd.modTime,
                        size = fd.size,
                        crlf = crlf,
                        editable = dec.second && fd.size <= EDIT_MAX,
                    )
                }
            }
            loading = false
            r.onSuccess { open = it }
                .onFailure { Toast.makeText(ctx, it.message ?: "열 수 없습니다", Toast.LENGTH_LONG).show() }
        }
    }

    // First open: the terminal's folder (tmux pane), else home.
    LaunchedEffect(reloadKey) {
        val start = dir ?: withContext(Dispatchers.IO) { session.currentDir() } ?: "~"
        load(start)
    }

    val file = open
    if (file != null) {
        FileViewer(
            session = session,
            initial = file,
            fontSize = fontSize,
            onInsertPath = { onInsertPath(shellPath(it) + " ") },
            onClose = { open = null; dir?.let { load(it) } },
        )
        return
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().background(Bg).imePadding()) {
        Row(Modifier.fillMaxWidth().background(Panel).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕") }
            TextButton(enabled = dir != null && dir != "/", onClick = { dir?.let { load(parent(it)) } }) { Text("↑") }
            Text(
                // The end of the path matters most: cut from the front.
                (dir ?: "…").let { if (it.length > 42) "…" + it.takeLast(41) else it },
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).combinedClickableNoRipple(onClick = { pathEdit = dir ?: "" }),
            )
            if (loading) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).width(18.dp).height(18.dp), strokeWidth = 2.dp)
            var more by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { more = true }) { Text("⋮") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("⟳  새로고침") }, onClick = { more = false; dir?.let { load(it) } })
                    DropdownMenuItem(text = { Text("📍  터미널 위치로") }, onClick = {
                        more = false
                        scope.launch { load(withContext(Dispatchers.IO) { session.currentDir() } ?: "~") }
                    })
                    DropdownMenuItem(text = { Text("🏠  홈 폴더") }, onClick = { more = false; load("~") })
                    DropdownMenuItem(text = { Text("✎  경로 직접 입력") }, onClick = { more = false; pathEdit = dir ?: "" })
                    DropdownMenuItem(
                        text = { Text(if (showHidden) "숨김 파일 숨기기" else "숨김 파일 보이기") },
                        onClick = { more = false; showHidden = !showHidden },
                    )
                    DropdownMenuItem(text = { Text("⌨  이 폴더 경로 입력") }, onClick = {
                        more = false
                        dir?.let { onInsertPath(shellPath(it) + " "); onClose() }
                    })
                }
            }
        }
        if (entries.size > 12 || filter.isNotEmpty()) {
            OutlinedTextField(
                filter, { filter = it }, placeholder = { Text("이름으로 거르기") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        val err = error
        if (err != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("열 수 없어요: $err", color = Color(0xFFEF9A9A))
                Row {
                    TextButton(onClick = { dir?.let { load(it) } ?: run { reloadKey++ } }) { Text("다시 시도") }
                    TextButton(onClick = { load("~") }) { Text("홈 폴더로") }
                }
            }
        }
        val shown = entries.filter { (showHidden || !it.name.startsWith(".")) && (filter.isBlank() || it.name.contains(filter, true)) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.name }) { e ->
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickableNoRipple(
                                onClick = { val p = child(dir ?: "", e.name); if (e.dir) load(p) else openFile(p) },
                                onLongClick = { menuFor = e },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (e.dir) "📁" else if (e.link) "🔗" else "📄", fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            e.name + if (e.dir) "/" else "",
                            color = if (e.name.startsWith(".")) Color(0xFFB0B0B8) else Fg,
                            fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (if (e.dir) "" else human(e.size) + " · ") + ago(e.mtime),
                            color = Dim, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = menuFor == e, onDismissRequest = { menuFor = null }) {
                        val p = child(dir ?: "", e.name)
                        DropdownMenuItem(text = { Text("⌨  터미널에 경로 입력") }, onClick = {
                            menuFor = null; onInsertPath(shellPath(p) + " "); onClose()
                        })
                        DropdownMenuItem(text = { Text("📋  경로 복사") }, onClick = { menuFor = null; copyText(ctx, p) })
                    }
                }
                HorizontalDivider(color = Color(0xFF22222A))
            }
            if (shown.isEmpty() && !loading && error == null) {
                item { Text("비어 있어요", color = Dim, modifier = Modifier.padding(16.dp)) }
            }
        }
    }

    val pe = pathEdit
    if (pe != null) {
        var v by remember { mutableStateOf(pe) }
        AlertDialog(
            onDismissRequest = { pathEdit = null },
            title = { Text("폴더 또는 파일 경로") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true, placeholder = { Text("~/project/.env") }) },
            confirmButton = {
                TextButton(onClick = {
                    pathEdit = null
                    val target = v.trim()
                    // A path to a file opens it; anything else is listed as a folder.
                    scope.launch {
                        val isFile = withContext(Dispatchers.IO) {
                            runCatching { session.listDir(target); false }.getOrDefault(true)
                        }
                        if (isFile) openFile(target) else load(target)
                    }
                }) { Text("열기") }
            },
            dismissButton = { TextButton(onClick = { pathEdit = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoRipple(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
private fun FileViewer(
    session: TermSession,
    initial: OpenFile,
    fontSize: Int,
    onInsertPath: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val font = d2Coding()
    var file by remember { mutableStateOf(initial) }
    val lang = remember(file.path) { Syntax.languageFor(file.name, file.text.substringBefore('\n')) }
    val isEnv = lang === Syntax.ENV
    var wrap by remember { mutableStateOf(true) }
    var fs by remember { mutableIntStateOf(fontSize.coerceIn(10, 24)) }
    var mask by remember(file.path) { mutableStateOf(isEnv) }
    var editing by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var findIndex by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }

    val lines = remember(file.text) { file.text.split('\n').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } }
    var spans by remember(file.text, lang) { mutableStateOf<List<List<Syntax.Span>>?>(null) }
    LaunchedEffect(file.text, lang) {
        spans = withContext(Dispatchers.Default) { Syntax.highlight(lines, lang) }
    }
    val matches = remember(lines, find) { if (find.isEmpty()) emptyList() else lines.indices.filter { lines[it].contains(find, true) } }
    val listState = rememberLazyListState()
    LaunchedEffect(matches, findIndex) {
        if (matches.isNotEmpty()) listState.animateScrollToItem(matches[findIndex.coerceIn(0, matches.size - 1)])
    }

    if (editing) {
        FileEditor(
            session = session,
            file = file,
            lang = lang,
            fontSize = fs,
            onSaved = { file = it },
            onClose = { editing = false },
        )
        return
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().background(Bg).imePadding()) {
        Row(Modifier.fillMaxWidth().background(Panel).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(file.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${lang.name} · ${lines.size}줄 · ${human(file.size)}" + if (!file.editable) " · 읽기 전용" else "",
                    color = Dim, fontSize = 10.sp, maxLines = 1,
                )
            }
            TextButton(onClick = { findOpen = !findOpen; if (!findOpen) find = "" }) { Text("🔍") }
            if (file.editable) TextButton(onClick = { editing = true }) { Text("✎") }
            Box {
                TextButton(onClick = { more = true }) { Text("⋮") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (wrap) "줄바꿈 끄기 (가로 스크롤)" else "줄바꿈 켜기 (화면 폭 맞춤)") }, onClick = { more = false; wrap = !wrap })
                    DropdownMenuItem(text = { Text("A+  글자 크게") }, onClick = { fs = (fs + 1).coerceAtMost(30) })
                    DropdownMenuItem(text = { Text("A−  글자 작게") }, onClick = { fs = (fs - 1).coerceAtLeast(8) })
                    if (isEnv) DropdownMenuItem(text = { Text(if (mask) "👁  값 보이기" else "●  값 가리기") }, onClick = { more = false; mask = !mask })
                    DropdownMenuItem(text = { Text("⌨  터미널에 경로 입력") }, onClick = { more = false; onInsertPath(file.path) })
                    DropdownMenuItem(text = { Text("📋  경로 복사") }, onClick = { more = false; copyText(ctx, file.path) })
                    DropdownMenuItem(text = { Text("📋  내용 전체 복사") }, onClick = { more = false; copyText(ctx, file.text) })
                    DropdownMenuItem(text = { Text("⟳  다시 불러오기") }, onClick = {
                        more = false
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { reread(session, file.path) } }
                                .onSuccess { file = it }
                                .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                        }
                    })
                }
            }
        }
        if (findOpen) {
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    find, { find = it; findIndex = 0 }, singleLine = true, placeholder = { Text("찾기") },
                    modifier = Modifier.weight(1f),
                )
                Text(if (matches.isEmpty()) "0" else "${findIndex + 1}/${matches.size}", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                TextButton(enabled = matches.isNotEmpty(), onClick = { findIndex = (findIndex - 1 + matches.size) % matches.size }) { Text("▲") }
                TextButton(enabled = matches.isNotEmpty(), onClick = { findIndex = (findIndex + 1) % matches.size }) { Text("▼") }
            }
        }
        if (isEnv && mask) {
            Text(
                "값을 가렸어요 (⋮ → 값 보이기)",
                color = Dim, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFF17171D)).padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        val gutterDigits = lines.size.toString().length
        val style = TextStyle(fontFamily = font, fontSize = fs.sp, lineHeight = (fs * 1.4f).sp, color = Fg)
        val gutterStyle = style.copy(color = Color(0xFF5A5A66))
        val density = LocalDensity.current
        // Without wrapping, the whole list scrolls sideways together: give it the width of the
        // longest line (cells × an upper bound of D2Coding's half-width advance).
        val maxCells = remember(lines) { lines.maxOfOrNull { l -> l.sumOf { c -> if (c.code >= 0x1100) 2L else 1L } }?.toInt() ?: 0 }
        val contentWidth = with(density) { (fs.sp.toDp() * 0.62f) * (maxCells + gutterDigits + 3).coerceAtMost(2000) }

        SelectionContainer(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))) {
                LazyColumn(
                    state = listState,
                    modifier = if (wrap) Modifier.fillMaxSize() else Modifier.width(contentWidth).fillMaxHeight(),
                ) {
                    itemsIndexed(lines) { i, raw ->
                        val shownText = if (mask) maskEnv(raw) else raw
                        val text = remember(shownText, spans, find) {
                            styled(shownText, spans?.getOrNull(i) ?: emptyList(), find)
                        }
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                            Text(
                                (i + 1).toString().padStart(gutterDigits),
                                style = gutterStyle,
                                modifier = Modifier.background(Color(0xFF15151A)).padding(start = 4.dp, end = 6.dp),
                            )
                            Text(
                                text, style = style, softWrap = wrap,
                                modifier = Modifier.padding(start = 6.dp).then(if (wrap) Modifier.weight(1f) else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun reread(session: TermSession, path: String): OpenFile {
    val fd = session.readFile(path, VIEW_MAX)
    val dec = decode(fd.data) ?: throw IllegalStateException("바이너리 파일이라 열 수 없어요")
    val crlf = dec.first.contains("\r\n")
    return OpenFile(fd.path, if (crlf) dec.first.replace("\r\n", "\n") else dec.first, fd.modTime, fd.size, crlf, dec.second && fd.size <= EDIT_MAX)
}

/**
 * Syntax colours while editing (the text itself is unchanged: identity offsets).
 *
 * Stays on the value-based BasicTextField + VisualTransformation (deprecated in Compose 1.13):
 * the state-based replacement can only colour text through OutputTransformation.addStyle,
 * which is still behind ComposeFoundationFlags.isBasicTextFieldStyledTextEnabled.
 * Migrate once styled text is on by default.
 */
@Suppress("DEPRECATION")
private class HighlightTransformation(private val lang: Syntax.Lang) : VisualTransformation {
    private var lastText: String? = null
    private var last: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        if (lang === Syntax.PLAIN || s.length > EDIT_HIGHLIGHT_MAX) return TransformedText(text, OffsetMapping.Identity)
        val cached = last
        val out = if (s == lastText && cached != null) cached else {
            val lines = s.split('\n')
            val spans = Syntax.highlight(lines, lang)
            buildAnnotatedString {
                append(s)
                var off = 0
                lines.forEachIndexed { i, l ->
                    for (sp in spans[i]) {
                        val e = sp.end.coerceAtMost(l.length)
                        if (sp.start < e) addStyle(tokColor(sp.tok), off + sp.start, off + e)
                    }
                    off += l.length + 1
                }
            }.also { lastText = s; last = it }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

@Suppress("DEPRECATION") // value-based BasicTextField: see HighlightTransformation
@Composable
private fun FileEditor(
    session: TermSession,
    file: OpenFile,
    lang: Syntax.Lang,
    fontSize: Int,
    onSaved: (OpenFile) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val font = d2Coding()
    var base by remember { mutableStateOf(file) }
    var value by remember { mutableStateOf(TextFieldValue(file.text)) }
    var saving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var leaveAsk by remember { mutableStateOf(false) }
    val dirty = value.text != base.text
    val transformation = remember(lang) { HighlightTransformation(lang) }

    fun save(force: Boolean) {
        if (saving) return
        saving = true
        val text = value.text
        val bytes = (if (base.crlf) text.replace("\n", "\r\n") else text).toByteArray(Charsets.UTF_8)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    session.writeFile(base.path, bytes, if (force) 0 else base.modTime, if (force) 0 else base.size)
                }
            }
            saving = false
            r.onSuccess { mtime ->
                base = OpenFile(base.path, text, mtime, bytes.size.toLong(), base.crlf, true)
                onSaved(base)
                Toast.makeText(ctx, "저장했습니다", Toast.LENGTH_SHORT).show()
            }.onFailure {
                if (it.message?.contains(TermSession.CONFLICT) == true) conflict = true
                else Toast.makeText(ctx, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler { if (dirty) leaveAsk = true else onClose() }

    Column(
        Modifier.fillMaxSize().background(Bg).imePadding().onPreviewKeyEvent { e ->
            // Ctrl+S on a hardware keyboard.
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.S) { save(false); true } else false
        },
    ) {
        Row(Modifier.fillMaxWidth().background(Panel).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (dirty) leaveAsk = true else onClose() }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text((if (dirty) "● " else "") + base.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("편집 중 · Ctrl+S 저장", color = Dim, fontSize = 10.sp, maxLines = 1)
            }
            if (saving) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).width(18.dp).height(18.dp), strokeWidth = 2.dp)
            TextButton(enabled = dirty && !saving, onClick = { save(false) }) { Text("저장") }
        }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = TextStyle(fontFamily = font, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4f).sp, color = Fg),
            cursorBrush = SolidColor(Color(0xFF90CAF9)),
            visualTransformation = transformation,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        )
    }

    if (conflict) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            title = { Text("서버 파일이 바뀌었어요") },
            text = { Text("이 파일을 연 뒤에 서버에서 내용이 바뀌었어요 (Claude가 고쳤을 수 있어요).\n덮어쓰면 서버 쪽 변경이 사라집니다.") },
            confirmButton = { TextButton(onClick = { conflict = false; save(true) }) { Text("덮어쓰기") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        conflict = false
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { reread(session, base.path) } }
                                .onSuccess { base = it; value = TextFieldValue(it.text); onSaved(it) }
                                .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("서버 내용 불러오기") }
                    TextButton(onClick = { conflict = false }) { Text("취소") }
                }
            },
        )
    }
    if (leaveAsk) {
        AlertDialog(
            onDismissRequest = { leaveAsk = false },
            title = { Text("저장하지 않은 변경") },
            text = { Text("고친 내용을 저장할까요?") },
            confirmButton = { TextButton(onClick = { leaveAsk = false; save(false) }) { Text("저장") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaveAsk = false; onClose() }) { Text("버리고 나가기") }
                    TextButton(onClick = { leaveAsk = false }) { Text("취소") }
                }
            },
        )
    }
}
