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
import java.io.ByteArrayOutputStream
import kotlin.math.abs

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
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = ByteArrayOutputStream()
    private var scheduled = false
    private var ready = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    init {
        settings.javaScriptEnabled = true
        settings.textZoom = 100
        settings.setSupportZoom(false)
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.BLACK)
    }

    fun bind(fontSize: Int, cb: Callbacks) {
        addJavascriptInterface(Bridge(cb), "Bridge")
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime }
                MotionEvent.ACTION_UP -> if (abs(e.x - downX) < slop && abs(e.y - downY) < slop && e.eventTime - downT < 300) cb.onTap()
            }
            false
        }
        loadUrl("file:///android_asset/term/index.html#fs=$fontSize")
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

    fun setFontSize(n: Int) = evaluateJavascript("setFontSize($n)", null)

    fun selectionText(cb: (String) -> Unit) = evaluateJavascript("getSelectionText()") { json ->
        val text = runCatching { JSONArray("[$json]").optString(0) }.getOrDefault("")
        cb(if (json == "null") "" else text)
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
    }
}
