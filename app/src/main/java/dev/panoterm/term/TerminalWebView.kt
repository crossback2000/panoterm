package dev.panoterm.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import androidx.core.net.toUri

/** A table extracted from the terminal buffer for the table lens. */
data class TermTable(val header: Boolean, val rows: List<List<String>>)

/** Scrollback search result: [count] matches, the shown one is [index] (oldest = 0, -1 = none). */
data class SearchHit(val count: Int, val index: Int)

/** Claude Code approval prompt detected on screen. */
data class ApprovalPrompt(val question: String, val options: List<Pair<String, String>>)

/**
 * Renders the terminal with xterm.js. Never takes focus: keyboard input goes
 * through [TerminalInputView] / MainActivity so hardware keys are handled natively.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TerminalWebView(ctx: Context) : WebView(ctx) {
    interface Callbacks {
        fun onReady(cols: Int, rows: Int)
        fun onResize(cols: Int, rows: Int)
        fun onTermData(bytes: ByteArray)
        fun onModes(appCursor: Boolean, bracketedPaste: Boolean)
        fun onTap()
        fun onFontSize(size: Int)
        /** [confirm]: the visible text may not be the address (OSC 8 link): ask before opening. */
        fun onOpenUrl(url: String, confirm: Boolean)
        fun onPrompt(prompt: ApprovalPrompt?)
        fun onTables(count: Int)
        fun onGesture(name: String)
        /** Long-press started a text selection (for haptic feedback). */
        fun onSelectStart()
        /**
         * Selection changed. [dragging]: the finger is still extending it. [rect]: the selected
         * area in view pixels (visible part), for placing the copy/paste toolbar.
         */
        fun onSelection(active: Boolean, dragging: Boolean, rect: android.graphics.Rect?)
        /** Long-press on blank space at ([x], [y]) view pixels: offer paste. */
        fun onPasteMenu(x: Int, y: Int)
        /** The WebView's renderer process died; this view is unusable and must be replaced. */
        fun onRendererGone()
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = ByteArrayOutputStream()
    private var scheduled = false
    private var ready = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var multiTouch = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    init {
        if (dev.panoterm.BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("PanoWeb", "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
        }
        // MATCH_PARENT is essential: with WRAP_CONTENT (the AndroidView default) the CSS viewport
        // height is derived from the content height, which is 0 for our fixed-position layout.
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        // Only file:///android_asset/ is needed (unaffected by these); block everything else.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.textZoom = 100
        settings.setSupportZoom(false)
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.BLACK)
        // The terminal keeps running while its screen is closed (it belongs to the session), so
        // its renderer must not be the first thing Android kills when it is not visible.
        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
    }

    fun bind(fontSize: Int, fixedCols: Int, renderer: String, cb: Callbacks) {
        addJavascriptInterface(Bridge(cb), "Bridge")
        webViewClient = object : android.webkit.WebViewClient() {
            // The page never navigates by itself. Anything else (a link, window.open, a
            // redirect) would load a foreign page that still has the Bridge interface, i.e.
            // could type into the SSH session — so block it and open web links in the browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith(ASSET_ROOT)) return false
                if (request.hasGesture() && isWebUrl(url)) main.post { cb.onOpenUrl(url, true) }
                return true
            }

            // Without this the whole app is killed along with the renderer.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post { cb.onRendererGone() }
                return true
            }
        }
        // The page does its own long-press selection; suppress the WebView's native one.
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        // Tap detection only: returns false, so the WebView still gets every event and does its
        // own click/accessibility handling. ClickableViewAccessibility wants performClick() on a
        // tap, but that plays the system touch sound on every terminal tap.
        @SuppressLint("ClickableViewAccessibility")
        val tapDetector = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; multiTouch = false }
                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
                MotionEvent.ACTION_UP -> if (!multiTouch && abs(e.x - downX) < slop && abs(e.y - downY) < slop && e.eventTime - downT < 300) cb.onTap()
            }
            false
        }
        setOnTouchListener(tapDetector)
        // Debug builds: console diagnostics; scroll benchmark only with `adb shell setprop debug.panoterm.bench 1`.
        // Release builds never touch the hidden SystemProperties API.
        val debug = if (dev.panoterm.BuildConfig.DEBUG) {
            @SuppressLint("PrivateApi")
            val bench = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.panoterm.bench") == "1"
            }.getOrDefault(false)
            "&debug=1" + (if (bench) "&bench=1" else "")
        } else ""
        loadUrl("${ASSET_ROOT}index.html#fs=$fontSize&cols=$fixedCols&r=$renderer$debug")
    }

    /**
     * Main thread only. Output is coalesced and flushed once per frame. While the view is not on
     * screen there are no frames (postOnAnimation would hold everything until it is shown again),
     * so output is flushed on a short timer instead and the terminal stays current.
     */
    fun write(b: ByteArray) {
        pending.write(b)
        if (ready && !scheduled) scheduleFlush()
    }

    private fun scheduleFlush() {
        scheduled = true
        if (isAttachedToWindow) postOnAnimation { flush() } else main.postDelayed({ flush() }, 50)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A flush queued with postOnAnimation would wait until the view is shown again.
        if (scheduled) main.post { flush() }
    }

    private var destroyed = false
    override fun destroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        super.destroy()
    }

    private fun flush() {
        scheduled = false
        if (destroyed || pending.size() == 0) return
        val b64 = Base64.encodeToString(pending.toByteArray(), Base64.NO_WRAP)
        pending.reset()
        evaluateJavascript("termWrite('$b64')", null)
    }

    private fun js(code: String) { if (ready) evaluateJavascript(code, null) }

    fun setFontSize(n: Int) = js("setFontSize($n)")
    fun setRenderer(r: String) = js("setRenderer('${r.filter { it.isLetter() }}')")
    fun setFixedCols(n: Int) = js("setFixedCols($n)")
    fun panBy(pages: Float) = js("panBy($pages)")
    fun followCursor() = js("followCursor()")
    /** Height (device px) of native bars drawn over the bottom of the terminal. */
    fun setBottomInset(px: Int) {
        bottomInsetPx = px
        js("setBottomInset(${px / resources.displayMetrics.density})")
    }
    private var bottomInsetPx = 0

    private fun jsString(json: String?): String =
        if (json == null || json == "null") "" else runCatching { JSONArray("[$json]").optString(0) }.getOrDefault("")

    fun selectionText(cb: (String) -> Unit) = evaluateJavascript("getSelectionText()") { cb(jsString(it)) }
    fun selectAllText() = js("selectAllText()")
    fun clearSelectionText() = js("clearSelectionText()")

    /** Current input line up to the cursor (for autocomplete). */
    fun inputLine(cb: (String) -> Unit) {
        if (!ready) return
        evaluateJavascript("getInputLine()") { cb(jsString(it)) }
    }

    /** Input line, Claude Code box/menu and cursor position in one call (for autocomplete). */
    fun inputContext(cb: (InputContext) -> Unit) {
        if (!ready) return
        evaluateJavascript("getInputContext()") { json ->
            val ctx = runCatching {
                val o = JSONObject(jsString(json))
                val menu = o.optJSONArray("menu")
                val d = resources.displayMetrics.density
                val c = o.optJSONObject("cursor")
                InputContext(
                    line = o.optString("line"),
                    claude = o.optBoolean("claude"),
                    menu = if (menu == null) emptyList() else (0 until menu.length()).map { i ->
                        val e = menu.getJSONArray(i); e.optString(0) to e.optString(1)
                    },
                    cursorTop = c?.let { (it.optDouble("top") * d).toInt() } ?: -1,
                    cursorBottom = c?.let { (it.optDouble("bottom") * d).toInt() } ?: -1,
                )
            }.getOrNull() ?: return@evaluateJavascript
            cb(ctx)
        }
    }

    /** Text being composed by the keyboard, drawn at the cursor ("" hides it). */
    fun setComposition(text: String) = js("setComposition(${JSONObject.quote(text)})")

    /** Scrollback search in the page's own buffer: jumps to the newest match of [query]. */
    fun searchFind(query: String, cb: (SearchHit) -> Unit) = searchCall("searchFind(${JSONObject.quote(query)})", cb)

    /** Next match: [older] = further back in the output. Wraps around. */
    fun searchStep(older: Boolean, cb: (SearchHit) -> Unit) = searchCall("searchStep(${if (older) -1 else 1})", cb)

    /** Removes the highlight and scrolls back to the bottom. */
    fun searchClear() = js("searchClear()")

    private fun searchCall(code: String, cb: (SearchHit) -> Unit) {
        if (!ready) return cb(SearchHit(0, -1))
        evaluateJavascript(code) { json ->
            val o = runCatching { JSONObject(jsString(json)) }.getOrNull()
            cb(SearchHit(o?.optInt("n") ?: 0, o?.optInt("i", -1) ?: -1))
        }
    }

    fun readerText(lastResponseOnly: Boolean, cb: (String) -> Unit) =
        evaluateJavascript("getReaderText('${if (lastResponseOnly) "last" else "recent"}')") { cb(jsString(it)) }

    fun tables(cb: (List<TermTable>) -> Unit) = evaluateJavascript("getTables()") { json ->
        val list = runCatching {
            val arr = JSONArray(jsString(json))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val rows = o.getJSONArray("rows")
                TermTable(
                    header = o.optBoolean("header"),
                    rows = (0 until rows.length()).map { r ->
                        val row = rows.getJSONArray(r)
                        (0 until row.length()).map { c -> row.optString(c) }
                    },
                )
            }
        }.getOrDefault(emptyList())
        cb(list)
    }

    private inner class Bridge(private val cb: Callbacks) {
        @JavascriptInterface
        fun onReady(cols: Int, rows: Int) = main.post {
            ready = true
            if (pending.size() > 0 && !scheduled) scheduleFlush()
            if (bottomInsetPx > 0) setBottomInset(bottomInsetPx)
            cb.onReady(cols, rows)
        }

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) = main.post { cb.onResize(cols, rows) }

        @JavascriptInterface
        fun onTermData(data: String) = cb.onTermData(data.toByteArray(Charsets.UTF_8))

        @JavascriptInterface
        fun onTermBinary(data: String) = cb.onTermData(data.toByteArray(Charsets.ISO_8859_1))

        @JavascriptInterface
        fun onModes(appCursor: Boolean, bracketedPaste: Boolean) = cb.onModes(appCursor, bracketedPaste)

        @JavascriptInterface
        fun onFontSize(size: Int) = main.post { cb.onFontSize(size) }

        @JavascriptInterface
        fun openUrl(url: String) { if (isWebUrl(url)) main.post { cb.onOpenUrl(url, false) } }

        /** OSC 8 hyperlink: the program chose the text shown, so the address needs a look first. */
        @JavascriptInterface
        fun openLink(url: String) { if (isWebUrl(url)) main.post { cb.onOpenUrl(url, true) } }

        @JavascriptInterface
        fun onTables(count: Int) = main.post { cb.onTables(count) }

        @JavascriptInterface
        fun onGesture(name: String) = main.post { cb.onGesture(name) }

        @JavascriptInterface
        fun onSelectStart() = main.post { cb.onSelectStart() }

        @JavascriptInterface
        fun onSelection(active: Boolean, dragging: Boolean, l: Float, t: Float, r: Float, b: Float) {
            val d = resources.displayMetrics.density
            val rect = if (active) android.graphics.Rect((l * d).toInt(), (t * d).toInt(), (r * d).toInt(), (b * d).toInt()) else null
            main.post { cb.onSelection(active, dragging, rect) }
        }

        @JavascriptInterface
        fun onPasteMenu(x: Float, y: Float) {
            val d = resources.displayMetrics.density
            main.post { cb.onPasteMenu((x * d).toInt(), (y * d).toInt()) }
        }

        @JavascriptInterface
        fun onPrompt(json: String) {
            val p = if (json.isBlank()) null else runCatching {
                val o = JSONObject(json)
                val opts = o.getJSONArray("opts")
                ApprovalPrompt(
                    question = o.optString("q"),
                    options = (0 until opts.length()).map { val x = opts.getJSONObject(it); x.optString("n") to x.optString("label") },
                )
            }.getOrNull()
            main.post { cb.onPrompt(p) }
        }
    }

    companion object {
        private const val ASSET_ROOT = "file:///android_asset/term/"

        /** Only http(s) links leave the terminal (no intent:, file:, javascript:, …). */
        fun isWebUrl(url: String): Boolean {
            val scheme = runCatching { url.toUri().scheme }.getOrNull()?.lowercase()
            return scheme == "http" || scheme == "https"
        }
    }
}
