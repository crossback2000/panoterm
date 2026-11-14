package dev.panoterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.panoterm.App
import dev.panoterm.MainActivity
import dev.panoterm.term.TermSession
import dev.panoterm.term.TerminalInputView
import dev.panoterm.term.TerminalWebView
import android.os.Handler
import android.os.Looper

@Composable
fun TerminalScreen(app: App, session: TermSession, activity: MainActivity, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by session.state.collectAsState()
    val message by session.message.collectAsState()

    var fontSize by remember { mutableIntStateOf(app.hosts.get(session.host.id)?.fontSize ?: session.host.fontSize) }
    var composing by remember { mutableStateOf("") }
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var showTmux by remember { mutableStateOf(session.host.tmux) }

    val webView = remember { TerminalWebView(ctx) }
    val input = remember { TerminalInputView(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun changeFont(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(8, 32)
        webView.setFontSize(fontSize)
        app.hosts.get(session.host.id)?.let { app.hosts.upsert(it.copy(fontSize = fontSize)) }
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

    /** App shortcuts on the K380. Ctrl+Shift+- is left alone (it is Ctrl+_ = undo in Claude Code). */
    fun shortcut(e: KeyEvent): Boolean {
        val cs = e.isCtrlPressed && e.isShiftPressed && !e.isAltPressed
        val ca = e.isCtrlPressed && e.isAltPressed
        return when {
            cs && e.keyCode == KeyEvent.KEYCODE_V -> { paste(); true }
            cs && e.keyCode == KeyEvent.KEYCODE_C -> { copySelection(); true }
            ca && (e.keyCode == KeyEvent.KEYCODE_EQUALS || e.keyCode == KeyEvent.KEYCODE_PLUS || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) -> { changeFont(+1); true }
            ca && (e.keyCode == KeyEvent.KEYCODE_MINUS || e.keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> { changeFont(-1); true }
            ca && e.keyCode == KeyEvent.KEYCODE_T -> { showTmux = !showTmux; true }
            else -> false
        }
    }

    DisposableEffect(session) {
        input.onBytes = { session.send(it) }
        input.appCursor = { session.appCursor }
        input.onShortcut = ::shortcut
        input.onComposing = { composing = it }
        input.onStickyChanged = { c, a -> ctrlOn = c; altOn = a }

        webView.bind(fontSize, object : TerminalWebView.Callbacks {
            override fun onReady(cols: Int, rows: Int) = session.onTerminalSize(cols, rows)
            override fun onResize(cols: Int, rows: Int) = session.onTerminalSize(cols, rows)
            override fun onTermData(bytes: ByteArray) = session.send(bytes)
            override fun onModes(appCursor: Boolean, bracketedPaste: Boolean) {
                session.appCursor = appCursor
                session.bracketedPaste = bracketedPaste
            }
            override fun onTap() = input.showKeyboard()
        })
        session.attach { bytes -> main.post { webView.write(bytes) } }
        activity.keyTarget = input
        input.requestFocus()

        onDispose {
            session.detach()
            if (activity.keyTarget === input) activity.keyTarget = null
            webView.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF15151A)).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallButton("←", onBack)
            Box(
                Modifier.size(8.dp).background(
                    when (state) {
                        TermSession.State.CONNECTED -> Color(0xFF4CAF50)
                        TermSession.State.CLOSED -> Color(0xFFE53935)
                        else -> Color(0xFFFFA000)
                    }
                )
            )
            Text(
                " ${session.host.title}",
                color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SmallButton("A−") { changeFont(-1) }
            SmallButton("A+") { changeFont(+1) }
            SmallButton("붙여넣기") { paste() }
            SmallButton(if (showTmux) "tmux▾" else "tmux▸") { showTmux = !showTmux }
            SmallButton("⌨") { input.showKeyboard() }
            SmallButton("✕") { app.sessions.close(session); onBack() }
        }

        if (state == TermSession.State.CLOSED) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF3E2723)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message ?: "연결 끊김", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = { session.connect() }) { Text("다시 연결") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            // Invisible focus holder for keyboard input.
            AndroidView(factory = { input }, modifier = Modifier.size(1.dp))
        }

        if (composing.isNotEmpty()) {
            Text(
                composing,
                color = Color.Black, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF59D)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        KeyBar(showTmux = showTmux, ctrlOn = ctrlOn, altOn = altOn) { k ->
            when (k) {
                is BarKey.Text -> input.sendText(k.text)
                is BarKey.Raw -> input.sendRaw(k.bytes)
                is BarKey.Code -> input.sendKeyCode(k.keyCode, k.shift)
                is BarKey.Toggle -> if (k.ctrl) input.toggleCtrl() else input.toggleAlt()
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(40.dp)) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
    }
}
