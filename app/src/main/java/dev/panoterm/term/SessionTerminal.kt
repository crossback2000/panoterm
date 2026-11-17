package dev.panoterm.term

import android.graphics.Rect
import dev.panoterm.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The terminal of one session: the xterm.js emulator (in its WebView) and its state.
 *
 * It lives exactly as long as the session, not as long as the screen showing it. The terminal
 * screen only attaches the view while it is open, so leaving and re-entering keeps everything
 * as it was (screen contents, scrollback, alternate screen, mouse and paste modes) instead of
 * rebuilding it from replayed output. Output keeps flowing in while no screen is open.
 *
 * Main thread only.
 */
class SessionTerminal(private val app: App, private val session: TermSession) {
    /** Events for the terminal screen while it shows this session. */
    interface Ui {
        fun onTap()
        fun onFontSize(size: Int)
        fun onOpenUrl(url: String)
        fun onPrompt(prompt: ApprovalPrompt?)
        fun onTables(count: Int)
        fun onGesture(name: String)
        fun onSelectStart()
        fun onSelection(active: Boolean, dragging: Boolean, rect: Rect?)
        fun onPasteMenu(x: Int, y: Int)
        /** The terminal had to be re-created and its previous contents are lost. */
        fun onRendererGone()
    }

    private var prompt: ApprovalPrompt? = null
    private var tables = 0
    private var selected = false
    /** Renderer setting the view was created with or last switched to. */
    private var renderer = ""

    private val callbacks = object : TerminalWebView.Callbacks {
        // Session-level: must work whether or not a screen is open.
        override fun onReady(cols: Int, rows: Int) = session.onTerminalSize(cols, rows)
        override fun onResize(cols: Int, rows: Int) = session.onTerminalSize(cols, rows)
        override fun onTermData(bytes: ByteArray) = session.send(bytes)
        override fun onModes(appCursor: Boolean, bracketedPaste: Boolean) {
            session.appCursor = appCursor
            session.bracketedPaste = bracketedPaste
        }
        override fun onPrompt(prompt: ApprovalPrompt?) { this@SessionTerminal.prompt = prompt; ui?.onPrompt(prompt) }
        override fun onTables(count: Int) { tables = count; ui?.onTables(count) }
        override fun onSelection(active: Boolean, dragging: Boolean, rect: Rect?) {
            selected = active
            ui?.onSelection(active, dragging, rect)
        }
        override fun onRendererGone() {
            // Rare (the renderer was killed or crashed): the emulator state is gone with it.
            val old = _view.value
            _view.value = create()
            old.destroy()
            ui?.onRendererGone()
        }

        // Screen-level: only meaningful while the terminal is on screen.
        override fun onTap() { ui?.onTap() }
        override fun onFontSize(size: Int) { ui?.onFontSize(size) }
        override fun onOpenUrl(url: String) { ui?.onOpenUrl(url) }
        override fun onGesture(name: String) { ui?.onGesture(name) }
        override fun onSelectStart() { ui?.onSelectStart() }
        override fun onPasteMenu(x: Int, y: Int) { ui?.onPasteMenu(x, y) }
    }

    private val _view = MutableStateFlow(create())
    /** Replaced only if the WebView renderer process dies. */
    val view: StateFlow<TerminalWebView> = _view

    /** What the terminal screen needs to know; null while no screen shows this session. */
    var ui: Ui? = null
        set(value) {
            field = value
            // A screen that (re)opens starts from the current state.
            value?.onPrompt(prompt)
            value?.onTables(tables)
            if (selected) _view.value.clearSelectionText()
            // Settings → 화면 그리기 방식 applies when the terminal is opened again.
            val r = app.settings.current.renderer
            if (value != null && r != renderer) { renderer = r; _view.value.setRenderer(r) }
        }

    init {
        session.attach { bytes -> app.mainExecutor.execute { _view.value.write(bytes) } }
    }

    private fun create(): TerminalWebView {
        val host = app.hosts.get(session.host.id) ?: session.host
        renderer = app.settings.current.renderer
        // Application context: the view outlives any one Activity.
        return TerminalWebView(app).apply { bind(host.fontSize, host.fixedCols, renderer, callbacks) }
    }

    fun destroy() {
        ui = null
        session.detach()
        _view.value.destroy()
    }
}
