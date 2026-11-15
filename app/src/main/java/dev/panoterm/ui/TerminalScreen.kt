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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.panoterm.App
import dev.panoterm.MainActivity
import dev.panoterm.data.PromptHistory
import dev.panoterm.term.ApprovalPrompt
import dev.panoterm.term.TermSession
import dev.panoterm.term.TermTable
import dev.panoterm.term.TerminalInputView
import dev.panoterm.term.TerminalWebView

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
    val sessions by app.sessions.sessions.collectAsState()
    val settings by app.settings.values.collectAsState()

    var fontSize by remember(session.id) { mutableIntStateOf(app.hosts.get(session.host.id)?.fontSize ?: session.host.fontSize) }
    var fixedCols by remember(session.id) { mutableIntStateOf(app.hosts.get(session.host.id)?.fixedCols ?: 0) }
    var composing by remember { mutableStateOf("") }
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var hangulOn by remember { mutableStateOf(false) }
    var showTmux by remember(session.id) { mutableStateOf(session.host.tmux) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var lensTables by remember { mutableStateOf<List<TermTable>>(emptyList()) }
    var tableCount by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf<ApprovalPrompt?>(null) }
    var composeOpen by remember { mutableStateOf(false) }
    var portsOpen by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    val history = remember { PromptHistory(ctx) }
    val snippets by app.snippets.items.collectAsState()

    @Composable
    fun MenuItem(label: String, action: () -> Unit) =
        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })

    val webView = remember(session.id) { TerminalWebView(ctx) }
    val input = remember(session.id) { TerminalInputView(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }

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
            if (t.isNotEmpty()) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", t))
                Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openLens() = webView.tables { lensTables = it; overlay = Overlay.LENS }

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

    /** App shortcuts on the K380. Ctrl+Shift+- is left alone (Ctrl+_ = undo in Claude Code). */
    fun shortcut(e: KeyEvent): Boolean {
        val cs = e.isCtrlPressed && e.isShiftPressed && !e.isAltPressed
        val ca = e.isCtrlPressed && e.isAltPressed
        return when {
            cs && e.keyCode == KeyEvent.KEYCODE_V -> { paste(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_C -> { copySelection(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_F -> { openLens(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_R -> { overlay = Overlay.READER; true }
            cs && e.keyCode == KeyEvent.KEYCODE_E -> { composeOpen = true; true }
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
            if (fixedCols > 0) main.post { webView.followCursor() }
        }
        input.appCursor = { session.appCursor }
        input.onShortcut = ::shortcut
        input.onComposing = { composing = it }
        input.onStickyChanged = { c, a -> ctrlOn = c; altOn = a }
        input.onHangulChanged = { hangulOn = it }
        input.settings = { app.settings.current }

        webView.bind(fontSize, fixedCols, object : TerminalWebView.Callbacks {
            override fun onReady(cols: Int, rows: Int) = session.onTerminalSize(cols, rows)
            override fun onResize(cols: Int, rows: Int) = session.onTerminalSize(cols, rows)
            override fun onTermData(bytes: ByteArray) = session.send(bytes)
            override fun onModes(appCursor: Boolean, bracketedPaste: Boolean) {
                session.appCursor = appCursor
                session.bracketedPaste = bracketedPaste
            }
            override fun onTap() = input.showKeyboard()
            override fun onFontSize(size: Int) {
                fontSize = size
                persist { it.copy(fontSize = size) }
            }
            override fun onOpenUrl(url: String) { openUrl(url) }
            override fun onPrompt(p: ApprovalPrompt?) { prompt = p }
            override fun onTables(count: Int) { tableCount = count }
            override fun onGesture(name: String) {
                // Two-finger swipe left = Esc (interrupt Claude).
                if (name == "swipe-left" && app.settings.current.twoFingerEsc) {
                    session.send("\u001b")
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(ctx, "Esc", Toast.LENGTH_SHORT).show()
                }
            }
        })
        session.attach { bytes -> main.post { webView.write(bytes) } }
        activity.keyTarget = input
        app.visibleSessionId = session.id
        input.requestFocus()

        onDispose {
            session.detach()
            if (activity.keyTarget === input) activity.keyTarget = null
            if (app.visibleSessionId == session.id) app.visibleSessionId = null
            webView.destroy()
        }
    }

    // Hardware keys go to the compose box while it is open.
    DisposableEffect(composeOpen, overlay) {
        activity.keyTarget = if (composeOpen) null else input
        onDispose {}
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
                        " ${session.host.title}" + if (sessions.size > 1) " ▾" else "",
                        color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp).then(
                            if (sessions.size > 1) Modifier.clickableNoFocus { sessionMenu = true } else Modifier
                        ),
                    )
                    DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                        sessions.forEach { s ->
                            DropdownMenuItem(
                                text = { Text((if (s.id == session.id) "● " else "") + s.host.title) },
                                onClick = { sessionMenu = false; if (s.id != session.id) onSwitch(s) },
                            )
                        }
                    }
                }
                // Frequently used actions stay visible; everything else lives in the ⋮ menu.
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    IconText(if (fixedCols == 0) "↔" else "↔$fixedCols") { cycleWidth() }
                    IconText("▦") { openLens() }
                    IconText("📖") { overlay = Overlay.READER }
                    IconText("✎") { composeOpen = !composeOpen }
                    IconText("⚡") { paletteOpen = true }
                    IconText(if (hangulOn) "한" else "A") { input.toggleHangul() }
                    Box {
                        IconText("⋮") { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MenuItem("🖼  이미지 보내기") { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            MenuItem("⇄  포트 미리보기") { portsOpen = true }
                            MenuItem("📋  붙여넣기") { paste() }
                            MenuItem("A+  글자 크게") { changeFont(+1) }
                            MenuItem("A−  글자 작게") { changeFont(-1) }
                            MenuItem(if (showTmux) "tmux 키 줄 숨기기" else "tmux 키 줄 보이기") { showTmux = !showTmux }
                            MenuItem("⌨  키보드 열기") { input.showKeyboard() }
                            MenuItem("✕  연결 끊고 닫기") { app.sessions.close(session); onBack() }
                        }
                    }
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

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                // Invisible focus holder for keyboard input.
                AndroidView(factory = { input }, modifier = Modifier.size(1.dp))
                if (tableCount > 0 && overlay == Overlay.NONE) {
                    Box(Modifier.align(Alignment.TopEnd)) { TablesChip(tableCount) { openLens() } }
                }
            }

            if (composing.isNotEmpty()) {
                Text(
                    composing,
                    color = Color.Black, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF59D)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            val p = prompt
            if (p != null && settings.approvalOverlay && !composeOpen) {
                ApprovalBar(p, onChoose = { n -> session.send(n) }, onEsc = { session.send("\u001b") })
            }

            if (composeOpen) {
                ComposeBox(
                    history = history.get(session.host.id),
                    onSend = { text, submit ->
                        history.add(session.host.id, text)
                        session.paste(text)
                        if (submit) main.postDelayed({ session.send("\r") }, 60)
                    },
                    onClose = { composeOpen = false; input.requestFocus() },
                )
            } else {
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
    if (paletteOpen) {
        CommandPalette(snippets, onDismiss = { paletteOpen = false }) { s ->
            paletteOpen = false
            session.paste(s.text)
            if (s.submit) main.postDelayed({ session.send("\r") }, 60)
        }
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
