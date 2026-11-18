package dev.panoterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.panoterm.App
import dev.panoterm.MainActivity
import dev.panoterm.term.ApprovalPrompt
import dev.panoterm.term.TermSession
import dev.panoterm.term.TermTable
import dev.panoterm.term.TerminalInputView
import dev.panoterm.term.SessionTerminal

/** Column presets for the wide viewport (0 = fit to screen). */
private val WIDTHS = listOf(0, 120, 160, 200)

private enum class Overlay { NONE, LENS, READER }

@Composable
fun TerminalScreen(
    app: App,
    session: TermSession,
    activity: MainActivity,
    onBack: () -> Unit,
    onSwitch: (TermSession) -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val state by session.state.collectAsState()
    val message by session.message.collectAsState()
    val reconnectIn by session.reconnectIn.collectAsState()
    val noTmux by session.noTmux.collectAsState()
    var noTmuxDismissed by remember(session.id) { mutableStateOf(false) }
    var installTmuxAsk by remember { mutableStateOf(false) }
    val sessions by app.sessions.sessions.collectAsState()
    val settings by app.settings.values.collectAsState()

    var fontSize by remember(session.id) { mutableIntStateOf(app.hosts.get(session.host.id)?.fontSize ?: session.host.fontSize) }
    var fixedCols by remember(session.id) { mutableIntStateOf(app.hosts.get(session.host.id)?.fixedCols ?: 0) }
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var showTmux by remember(session.id) { mutableStateOf(session.host.tmux) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var lensTables by remember { mutableStateOf<List<TermTable>>(emptyList()) }
    var tableCount by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf<ApprovalPrompt?>(null) }
    var portsOpen by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    val snippets by app.snippets.items.collectAsState()

    @Composable
    fun MenuItem(label: String, action: () -> Unit) =
        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })

    // The terminal belongs to the session and outlives this screen (see SessionTerminal).
    val terminal = remember(session.id) { app.sessions.terminal(session) }
    val webView by terminal.view.collectAsState()
    val toolbar = remember { TermToolbar() }
    val input = remember(session.id) { TerminalInputView(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val completer = remember(session.id) {
        dev.panoterm.term.Completer(session) { cb -> terminal.view.value.inputContext(cb) }.apply {
            enabled = { app.settings.current.autocomplete }
        }
    }
    val suggestions by completer.suggestions.collectAsState()
    val suggestAnchor by completer.anchor.collectAsState()

    fun persist(f: (dev.panoterm.data.Host) -> dev.panoterm.data.Host) {
        app.hosts.get(session.host.id)?.let { app.hosts.upsert(f(it)) }
    }

    fun changeFont(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(8, 32)
        webView.setFontSize(fontSize)
        persist { it.copy(fontSize = fontSize) }
    }

    fun cycleWidth() {
        val i = WIDTHS.indexOf(fixedCols).let { if (it < 0) 0 else it }
        fixedCols = WIDTHS[(i + 1) % WIDTHS.size]
        webView.setFixedCols(fixedCols)
        persist { it.copy(fixedCols = fixedCols) }
        Toast.makeText(ctx, if (fixedCols == 0) "화면 맞춤" else "넓게: ${fixedCols}열 (좌우로 밀어서 보기)", Toast.LENGTH_SHORT).show()
    }

    fun paste() {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (!text.isNullOrEmpty()) session.paste(text)
    }

    fun copySelection() {
        webView.selectionText { t ->
            if (t.isNotBlank()) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", t.trimEnd()))
                Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Types the selected text at the prompt (no clipboard involved). */
    fun typeSelection() {
        webView.selectionText { t ->
            if (t.isNotBlank()) session.paste(t.trimEnd())
            webView.clearSelectionText()
        }
    }

    /** Checks the clipboard's description only: reading the content would show Android's "pasted" notice. */
    fun clipboardHasText(): Boolean {
        val d = ctx.getSystemService(ClipboardManager::class.java).primaryClipDescription ?: return false
        return d.hasMimeType("text/*")
    }

    fun openLens() = webView.tables { lensTables = it; overlay = Overlay.LENS }

    var topBarEdit by remember { mutableStateOf(false) }

    fun openUrl(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = ctx.contentResolver.getType(uri) ?: "image/png"
        val ext = mime.substringAfter('/').substringBefore(';').replace("jpeg", "jpg").ifBlank { "png" }
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            Toast.makeText(ctx, "이미지를 읽을 수 없습니다", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        Toast.makeText(ctx, "업로드 중… (${bytes.size / 1024} KB)", Toast.LENGTH_SHORT).show()
        session.upload("image.$ext", bytes) { r ->
            r.onSuccess { path ->
                session.send("$path ")
                Toast.makeText(ctx, "업로드 완료: 경로를 입력했습니다", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(ctx, "업로드 실패: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun imagePickerLaunch() = imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun runTopAction(id: String) {
        when (id) {
            "width" -> cycleWidth()
            "lens" -> openLens()
            "reader" -> overlay = Overlay.READER
            "palette" -> paletteOpen = true
            "image" -> imagePickerLaunch()
            "ports" -> portsOpen = true
            "paste" -> paste()
            "fontUp" -> changeFont(+1)
            "fontDown" -> changeFont(-1)
            "tmux" -> showTmux = !showTmux
            "keyboard" -> input.showKeyboard()
            "close" -> { app.sessions.close(session); onBack() }
        }
    }

    /** App shortcuts on the K380. Ctrl+Shift+- is left alone (Ctrl+_ = undo in Claude Code). */
    fun shortcut(e: KeyEvent): Boolean {
        val cs = e.isCtrlPressed && e.isShiftPressed && !e.isAltPressed
        val ca = e.isCtrlPressed && e.isAltPressed
        return when {
            cs && e.keyCode == KeyEvent.KEYCODE_V -> { paste(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_C -> { copySelection(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_F -> { openLens(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_R -> { overlay = Overlay.READER; true }
            cs && e.keyCode == KeyEvent.KEYCODE_W -> { cycleWidth(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_P -> { paletteOpen = true; true }
            ca && (e.keyCode == KeyEvent.KEYCODE_EQUALS || e.keyCode == KeyEvent.KEYCODE_PLUS || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) -> { changeFont(+1); true }
            ca && (e.keyCode == KeyEvent.KEYCODE_MINUS || e.keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> { changeFont(-1); true }
            ca && e.keyCode == KeyEvent.KEYCODE_T -> { showTmux = !showTmux; true }
            e.isCtrlPressed && e.keyCode == KeyEvent.KEYCODE_TAB -> {
                app.sessions.next(session, if (e.isShiftPressed) -1 else 1)?.let(onSwitch); true
            }
            e.keyCode == KeyEvent.KEYCODE_ESCAPE && overlay != Overlay.NONE -> { overlay = Overlay.NONE; true }
            else -> false
        }
    }

    DisposableEffect(session.id) {
        input.onBytes = { b ->
            session.send(b)
            completer.onUserInput(b)
            if (fixedCols > 0) main.post { webView.followCursor() }
        }
        input.appCursor = { session.appCursor }
        input.onShortcut = ::shortcut
        // Drawn at the cursor by the page itself: no bar, nothing moves while typing Korean.
        input.onComposing = { terminal.view.value.setComposition(it) }
        input.onPaste = { session.paste(it) }
        input.onPasteRequest = { paste() }
        input.onStickyChanged = { c, a -> ctrlOn = c; altOn = a }
        input.settings = { app.settings.current }

        terminal.ui = object : SessionTerminal.Ui {
            override fun onTap() { toolbar.hide(); input.showKeyboard() }
            override fun onFontSize(size: Int) {
                fontSize = size
                persist { it.copy(fontSize = size) }
            }
            override fun onOpenUrl(url: String) { openUrl(url) }
            override fun onPrompt(p: ApprovalPrompt?) { prompt = p }
            override fun onTables(count: Int) { tableCount = count }
            override fun onSelectStart() {
                toolbar.hide()
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            override fun onSelection(active: Boolean, dragging: Boolean, rect: android.graphics.Rect?) {
                hasSelection = active
                if (!active || dragging || rect == null) { toolbar.hide(); return }
                val items = mutableListOf<Pair<String, () -> Unit>>(
                    "복사" to { copySelection(); terminal.view.value.clearSelectionText() },
                )
                if (clipboardHasText()) items += "붙여넣기" to { terminal.view.value.clearSelectionText(); paste() }
                items += "입력" to { typeSelection() }
                items += "전체 선택" to { terminal.view.value.selectAllText() }
                toolbar.show(terminal.view.value, rect, items)
            }
            override fun onPasteMenu(x: Int, y: Int) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                if (clipboardHasText()) toolbar.show(terminal.view.value, android.graphics.Rect(x, y - 8, x + 1, y + 8), listOf("붙여넣기" to { paste() }))
                else Toast.makeText(ctx, "클립보드가 비어 있습니다", Toast.LENGTH_SHORT).show()
            }
            override fun onGesture(name: String) {
                // Two-finger swipe left = Esc (interrupt Claude).
                if (name == "swipe-left" && app.settings.current.twoFingerEsc) {
                    session.send("\u001b")
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(ctx, "Esc", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onRendererGone() {
                Toast.makeText(ctx, "터미널 화면 엔진이 다시 시작되어 이전 화면 내용을 잃었습니다", Toast.LENGTH_LONG).show()
            }
        }
        activity.keyTarget = input
        app.visibleSessionId = session.id
        input.requestFocus()

        onDispose {
            completer.dispose()
            terminal.view.value.setComposition("")
            toolbar.hide()
            terminal.ui = null
            // Only take the view off this screen: the terminal lives on with the session.
            terminal.view.value.let { v -> (v.parent as? android.view.ViewGroup)?.removeView(v) }
            if (activity.keyTarget === input) activity.keyTarget = null
            if (app.visibleSessionId == session.id) app.visibleSessionId = null
        }
    }

    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- top bar
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF15151A)).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconText("←", onBack)
                Box(
                    Modifier.size(8.dp).background(
                        when (state) {
                            TermSession.State.CONNECTED -> Color(0xFF4CAF50)
                            TermSession.State.CLOSED -> Color(0xFFE53935)
                            else -> Color(0xFFFFA000)
                        }
                    )
                )
                // Title takes whatever is left; the action buttons always stay visible.
                Box(Modifier.weight(1f)) {
                    Text(
                        " ${session.host.title}" + (session.label?.let { " · $it" } ?: "") + if (sessions.size > 1) " ▾" else "",
                        color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp).then(
                            if (sessions.size > 1) Modifier.clickableNoFocus { sessionMenu = true } else Modifier
                        ),
                    )
                    DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                        sessions.forEach { s ->
                            DropdownMenuItem(
                                text = { Text((if (s.id == session.id) "● " else "") + s.host.title + (s.label?.let { " · $it" } ?: "")) },
                                onClick = { sessionMenu = false; if (s.id != session.id) onSwitch(s) },
                            )
                        }
                    }
                }
                // The user picks which actions are icons here (상단 아이콘 편집); the rest live in ⋮.
                val pinned = topActionsFor(settings.topBar)
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    pinned.forEach { a ->
                        val label = when (a.id) {
                            "width" -> if (fixedCols == 0) "↔" else "↔$fixedCols"
                            "tmux" -> if (showTmux) "⊟" else "⊞"
                            else -> a.icon
                        }
                        IconText(label) { runTopAction(a.id) }
                    }
                    Box {
                        IconText("⋮") { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            ALL_TOP_ACTIONS.filter { a -> pinned.none { it.id == a.id } }.forEach { a ->
                                val label = when (a.id) {
                                    "tmux" -> if (showTmux) "tmux 키 줄 숨기기" else "tmux 키 줄 보이기"
                                    else -> a.label
                                }
                                MenuItem("${a.icon}  $label") { runTopAction(a.id) }
                            }
                            MenuItem("✎  상단 아이콘 편집") { topBarEdit = true }
                        }
                    }
                }
            }

            if (noTmux && !noTmuxDismissed) {
                androidx.compose.runtime.LaunchedEffect(Unit) { showTmux = false } // tmux keys would just type letters
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF4E3B00)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "서버에서 tmux를 못 찾아 일반 셸로 접속했어요. 연결이 끊기면 Claude 작업도 같이 끝나요.",
                        color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { installTmuxAsk = true }) { Text("tmux 설치") }
                    TextButton(onClick = { noTmuxDismissed = true }) { Text("닫기") }
                }
            }

            if (state == TermSession.State.CLOSED) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF3E2723)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (message ?: "연결 끊김") + (reconnectIn?.let { " · ${it}초 후 자동 재연결" } ?: ""),
                        color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2,
                    )
                    TextButton(onClick = { session.connect() }) { Text("지금 연결") }
                }
            }

            // Terminal + key bar follow the soft keyboard: moved with its animation every
            // frame, resized once (see followKeyboard). Clipped so rows moved up go under the
            // top bar instead of over it.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Column(Modifier.fillMaxSize().followKeyboard()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The session's terminal view is moved into this screen while it is open.
                AndroidView(
                    factory = { c -> android.widget.FrameLayout(c) },
                    update = { box ->
                        if (webView.parent !== box) {
                            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                            box.removeAllViews()
                            box.addView(webView)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Invisible focus holder for keyboard input.
                AndroidView(factory = { input }, modifier = Modifier.size(1.dp))
                if (tableCount > 0 && overlay == Overlay.NONE && !hasSelection) {
                    Box(Modifier.align(Alignment.TopEnd)) { TablesChip(tableCount) { openLens() } }
                }
                // The approval bar is drawn over the terminal instead of taking layout height:
                // resizing the terminal makes Claude Code repaint its whole screen. The page
                // shifts its rows up by this height so the input line stays visible.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onSizeChanged { webView.setBottomInset(it.height) },
                ) {
                    val p = prompt
                    if (p != null && settings.approvalOverlay) {
                        ApprovalBar(p, onChoose = { n -> session.send(n) }, onEsc = { session.send("\u001b") })
                    }
                }

                // Autocomplete floats right next to the line being typed (above it, or below
                // when there is no room) and never moves the screen.
                if (settings.autocomplete && suggestions.isNotEmpty()) {
                    NextToCursor(suggestAnchor) {
                        SuggestionBar(suggestions) { s ->
                            session.send(s.insert)
                            completer.onUserInput(s.insert.toByteArray()) // look again (e.g. inside the chosen folder)
                        }
                    }
                }
            }

            KeyBar(keys = keysFor(settings.keyBar), showTmux = showTmux, ctrlOn = ctrlOn, altOn = altOn) { k ->
                when (k) {
                    is BarKey.Text -> input.sendText(k.text)
                    is BarKey.Raw -> input.sendRaw(k.bytes)
                    is BarKey.Code -> input.sendKeyCode(k.keyCode, k.shift)
                    is BarKey.Toggle -> if (k.ctrl) input.toggleCtrl() else input.toggleAlt()
                    is BarKey.Palette -> paletteOpen = true
                    is BarKey.Pad -> {}
                }
            }
            }
            }
        }

        when (overlay) {
            Overlay.LENS -> TableLens(lensTables, fontSize) { overlay = Overlay.NONE }
            Overlay.READER -> ReaderView(
                load = { lastOnly, cb -> webView.readerText(lastOnly, cb) },
                initialFont = fontSize,
            ) { overlay = Overlay.NONE }
            Overlay.NONE -> {}
        }
    }

    if (portsOpen) PortsDialog(session) { portsOpen = false }
    if (topBarEdit) TopBarEditorDialog(app) { topBarEdit = false }
    if (installTmuxAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { installTmuxAsk = false },
            title = { Text("서버에 tmux 설치") },
            text = {
                Text(
                    "새 탭을 열어 서버 종류를 알아서 확인하고 tmux를 설치해요 " +
                        "(apt, dnf, yum, pacman, zypper, apk, brew, pkg).\n\n" +
                        "• 지금 탭은 그대로 둡니다. 실행 중인 Claude는 영향 없어요.\n" +
                        "• sudo 비밀번호를 물으면 새 탭에서 직접 입력하세요.\n" +
                        "• 설치가 끝나면 그 탭이 바로 tmux로 들어갑니다. 다음 접속부터는 자동으로 tmux예요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    installTmuxAsk = false
                    noTmuxDismissed = true
                    val name = session.host.tmuxSession.ifBlank { "main" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    val s = app.sessions.openExtra(session.host, TermSession.installTmuxCommand(name), "tmux 설치")
                    onSwitch(s)
                }) { Text("설치하기") }
            },
            dismissButton = { TextButton(onClick = { installTmuxAsk = false }) { Text("취소") } },
        )
    }
    if (paletteOpen) {
        CommandPalette(snippets, onDismiss = { paletteOpen = false }) { s ->
            paletteOpen = false
            session.paste(s.text)
            if (s.submit) main.postDelayed({ session.send("\r") }, 60)
        }
    }
}

/**
 * Keeps the bottom of this content on top of the soft keyboard the way chat apps do
 * (developer.android.com, "Synchronize animation with the software keyboard"):
 *  - layout height changes once per keyboard animation, never per frame: it uses the smaller
 *    of the animation's start and end keyboard heights. Opening resizes when the animation
 *    ends, closing resizes when it starts, so the content is never shorter than the space
 *    above the keyboard and no gap shows.
 *  - every frame, the content is moved up by the rest of the current keyboard height
 *    (draw-only translation, no layout and no terminal resize).
 * One resize per animation means one SIGWINCH and one repaint for the program on the server.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modifier.followKeyboard(): Modifier {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // The root already pads for the navigation bar, which the IME insets include.
    val nav = WindowInsets.navigationBars.getBottom(density)
    val settled = (minOf(WindowInsets.imeAnimationSource.getBottom(density), WindowInsets.imeAnimationTarget.getBottom(density)) - nav)
        .coerceAtLeast(0)
    return this
        .graphicsLayer { translationY = -((ime.getBottom(density) - nav).coerceAtLeast(0) - settled).coerceAtLeast(0).toFloat() }
        .padding(bottom = with(density) { settled.toDp() })
}

/**
 * Places [content] (full width) just above the cursor row [anchor] (view pixels), or just
 * below it when there is no room above. Unknown anchor: at the bottom.
 */
@Composable
private fun NextToCursor(anchor: IntRange?, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, c ->
        val p = measurables.first().measure(c.copy(minWidth = c.maxWidth, minHeight = 0))
        val y = when {
            anchor == null -> c.maxHeight - p.height
            anchor.first - p.height >= 0 -> anchor.first - p.height
            else -> (anchor.last).coerceAtMost(c.maxHeight - p.height)
        }.coerceAtLeast(0)
        layout(c.maxWidth, c.maxHeight) { p.place(0, y) }
    }
}

@Composable
private fun IconText(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(40.dp).widthIn(min = 34.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, maxLines = 1)
    }
}

/** Tap handler that never takes focus (keeps the soft keyboard attached to the terminal). */
private fun Modifier.clickableNoFocus(onClick: () -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) { detectTapGestures { onClick() } })

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(40.dp)) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
    }
}

/**
 * Android's floating text toolbar (the one text fields show for a selection) for the terminal's
 * selection and for paste. Shown over [rect] (view pixels of the anchor view).
 */
private class TermToolbar {
    private var mode: android.view.ActionMode? = null
    private val rect = android.graphics.Rect()
    private var titles: List<String> = emptyList()

    fun show(anchor: android.view.View, r: android.graphics.Rect, items: List<Pair<String, () -> Unit>>) {
        rect.set(r)
        val m = mode
        if (m != null && titles == items.map { it.first }) { m.invalidateContentRect(); return }
        hide()
        titles = items.map { it.first }
        mode = anchor.startActionMode(object : android.view.ActionMode.Callback2() {
            override fun onCreateActionMode(m: android.view.ActionMode, menu: android.view.Menu): Boolean {
                items.forEachIndexed { i, (title, _) -> menu.add(0, i, i, title) }
                return true
            }
            override fun onPrepareActionMode(m: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(m: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val action = items.getOrNull(item.itemId)?.second
                m.finish()
                action?.invoke()
                return true
            }
            override fun onDestroyActionMode(m: android.view.ActionMode) { if (mode === m) mode = null }
            override fun onGetContentRect(m: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                outRect.set(rect)
            }
        }, android.view.ActionMode.TYPE_FLOATING)
    }

    fun hide() { mode?.let { mode = null; it.finish() } }
}
