package dev.panoterm.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** A table extracted from the terminal buffer for the table lens. */
data class TermTable(val header: Boolean, val rows: List<List<String>>)

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
        fun onOpenUrl(url: String)
        fun onPrompt(prompt: ApprovalPrompt?)
        fun onTables(count: Int)
        fun onGesture(name: String)
        /** Long-press started a text selection (for haptic feedback). */
        fun onSelectStart()
        /** Whether some text is currently selected (drives the copy bar). */
        fun onSelection(active: Boolean)
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
        settings.textZoom = 100
        settings.setSupportZoom(false)
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.BLACK)
    }

    fun bind(fontSize: Int, fixedCols: Int, renderer: String, cb: Callbacks) {
        addJavascriptInterface(Bridge(cb), "Bridge")
        // The page does its own long-press selection; suppress the WebView's native one.
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; multiTouch = false }
                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
                MotionEvent.ACTION_UP -> if (!multiTouch && abs(e.x - downX) < slop && abs(e.y - downY) < slop && e.eventTime - downT < 300) cb.onTap()
            }
            false
        }
        // Debug builds: console diagnostics; scroll benchmark only with `adb shell setprop debug.panoterm.bench 1`.
        val bench = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.panoterm.bench") == "1"
        }.getOrDefault(false)
        val debug = if (dev.panoterm.BuildConfig.DEBUG) "&debug=1" + (if (bench) "&bench=1" else "") else ""
        loadUrl("file:///android_asset/term/index.html#fs=$fontSize&cols=$fixedCols&r=$renderer$debug")
    }

    /** Main thread only. Output is coalesced and flushed once per frame. */
    fun write(b: ByteArray) {
        pending.write(b)
        if (ready && !scheduled) {
            scheduled = true
            postOnAnimation { flush() }
        }
    }

    private fun flush() {
        scheduled = false
        if (pending.size() == 0) return
        val b64 = Base64.encodeToString(pending.toByteArray(), Base64.NO_WRAP)
        pending.reset()
        evaluateJavascript("termWrite('$b64')", null)
    }

    private fun js(code: String) { if (ready) evaluateJavascript(code, null) }

    fun setFontSize(n: Int) = js("setFontSize($n)")
    fun setFixedCols(n: Int) = js("setFixedCols($n)")
    fun panBy(pages: Float) = js("panBy($pages)")
    fun followCursor() = js("followCursor()")

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
            if (pending.size() > 0) { scheduled = true; postOnAnimation { flush() } }
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
        fun openUrl(url: String) = main.post { cb.onOpenUrl(url) }

        @JavascriptInterface
        fun onTables(count: Int) = main.post { cb.onTables(count) }

        @JavascriptInterface
        fun onGesture(name: String) = main.post { cb.onGesture(name) }

        @JavascriptInterface
        fun onSelectStart() = main.post { cb.onSelectStart() }

        @JavascriptInterface
        fun onSelection(active: Boolean) = main.post { cb.onSelection(active) }

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
}
