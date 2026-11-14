package dev.panoterm.term

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.panoterm.data.Settings

/**
 * Invisible view that owns keyboard focus for the terminal.
 * - Soft keyboard text arrives through the InputConnection.
 * - Hardware (K380) keys are routed here from MainActivity.dispatchKeyEvent.
 * Also owns sticky Ctrl/Alt (key bar), CapsLock-as-Ctrl and the hardware Hangul mode.
 */
class TerminalInputView(ctx: Context) : View(ctx) {
    var onBytes: (ByteArray) -> Unit = {}
    var onShortcut: (KeyEvent) -> Boolean = { false }
    var appCursor: () -> Boolean = { false }
    var onComposing: (String) -> Unit = {}
    var onStickyChanged: (ctrl: Boolean, alt: Boolean) -> Unit = { _, _ -> }
    var onHangulChanged: (Boolean) -> Unit = {}
    var settings: () -> Settings.Values = { Settings.Values() }

    var stickyCtrl = false
        private set
    var stickyAlt = false
        private set
    var hangulMode = false
        private set

    private val hangul = Hangul()
    private var capsHeld = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    fun toggleCtrl() { stickyCtrl = !stickyCtrl; onStickyChanged(stickyCtrl, stickyAlt) }
    fun toggleAlt() { stickyAlt = !stickyAlt; onStickyChanged(stickyCtrl, stickyAlt) }

    fun toggleHangul() {
        commitHangul()
        hangulMode = !hangulMode
        onHangulChanged(hangulMode)
    }

    private fun clearSticky() {
        if (stickyCtrl || stickyAlt) {
            stickyCtrl = false; stickyAlt = false
            onStickyChanged(false, false)
        }
    }

    private fun commitHangul() {
        val s = hangul.flush()
        onComposing("")
        if (s.isNotEmpty()) onBytes(s.toByteArray())
    }

    private fun isHangulToggle(e: KeyEvent): Boolean = when (settings().hangulToggle) {
        Settings.HangulToggle.SHIFT_SPACE -> e.keyCode == KeyEvent.KEYCODE_SPACE && e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed
        Settings.HangulToggle.CTRL_SPACE -> e.keyCode == KeyEvent.KEYCODE_SPACE && e.isCtrlPressed && !e.isShiftPressed
        Settings.HangulToggle.RIGHT_ALT -> e.keyCode == KeyEvent.KEYCODE_ALT_RIGHT
        Settings.HangulToggle.OFF -> false
    } || e.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || e.keyCode == KeyEvent.KEYCODE_KANA

    /** Hardware key down. Returns true if consumed. */
    fun handleHardwareKey(e: KeyEvent): Boolean {
        val capsAsCtrl = settings().capsAsCtrl
        if (capsAsCtrl && e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) { capsHeld = true; return true }
        if (isHangulToggle(e)) { toggleHangul(); return true }
        if (onShortcut(e)) return true

        val ctrl = e.isCtrlPressed || capsHeld || stickyCtrl
        val alt = e.isAltPressed || stickyAlt

        if (hangulMode && !ctrl && !alt && !e.isMetaPressed) {
            if (e.keyCode == KeyEvent.KEYCODE_DEL && hangul.isComposing) {
                hangul.backspace()
                onComposing(hangul.composing)
                return true
            }
            val ch = e.getUnicodeChar(e.metaState and KeyEvent.META_CAPS_LOCK_ON.inv()).toChar()
            if (e.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && hangul.isHangulKey(ch)) {
                val out = hangul.key(ch)
                if (out.isNotEmpty()) onBytes(out.toByteArray())
                onComposing(hangul.composing)
                return true
            }
        }
        if (hangul.isComposing) commitHangul()

        val b = KeyEncoder.encode(e, appCursor(), ctrl, alt, stripCaps = capsAsCtrl) ?: return false
        clearSticky()
        onBytes(b)
        return true
    }

    /** Hardware key up. Returns true if consumed. */
    fun handleHardwareKeyUp(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK && settings().capsAsCtrl) { capsHeld = false; return true }
        return false
    }

    /** Key bar: special key by keycode (arrows, Tab, PgUp...). */
    fun sendKeyCode(keyCode: Int, shift: Boolean) {
        if (hangul.isComposing) commitHangul()
        val s = KeyEncoder.special(keyCode, shift, stickyCtrl, stickyAlt, appCursor()) ?: return
        clearSticky()
        onBytes(s.toByteArray())
    }

    /** Key bar / IME text. A single character honours sticky Ctrl/Alt. */
    fun sendText(t: String) {
        if (t.isEmpty()) return
        if (hangul.isComposing) commitHangul()
        val s = t.replace("\r\n", "\r").replace('\n', '\r')
        if (stickyCtrl || stickyAlt) {
            val cp = s.codePointAt(0)
            onBytes(KeyEncoder.encodeChar(cp, stickyCtrl, stickyAlt))
            clearSticky()
            val rest = s.substring(Character.charCount(cp))
            if (rest.isNotEmpty()) onBytes(rest.toByteArray())
        } else {
            onBytes(s.toByteArray())
        }
    }

    /** Raw bytes from the key bar (Esc, ^C, tmux prefix...). Clears sticky modifiers. */
    fun sendRaw(s: String) {
        if (hangul.isComposing) commitHangul()
        clearSticky()
        onBytes(s.toByteArray())
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                onComposing(composing)
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) {
                    val c = composing
                    composing = ""
                    onComposing("")
                    sendText(c)
                }
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                onComposing("")
                sendText(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { onBytes(byteArrayOf(0x7f)) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onBytes("\r".toByteArray())
                return true
            }
        }
    }
}
