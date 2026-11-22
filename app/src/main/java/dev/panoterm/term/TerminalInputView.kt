package dev.panoterm.term

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import dev.panoterm.data.Settings

/**
 * Invisible view that owns keyboard focus for the terminal.
 * - The phone keyboard edits a real text buffer ([ImeMirror]); what changes in it is sent to the
 *   terminal. Composition (Hangul, 천지인, kana ...) stays with the keyboard, whichever it is.
 * - Hardware (K380) keys reach [handleHardwareKey] from MainActivity.dispatchKeyEvent, after the
 *   phone keyboard had its turn (it composes Hangul for hardware keyboards too).
 * Also owns sticky Ctrl/Alt (key bar), CapsLock-as-Ctrl and the optional in-app Hangul mode.
 */
class TerminalInputView(ctx: Context) : View(ctx) {
    var onBytes: (ByteArray) -> Unit = {}
    var onShortcut: (KeyEvent) -> Boolean = { false }
    var appCursor: () -> Boolean = { false }
    var onComposing: (String) -> Unit = {}
    /** Text the keyboard pasted (sent as a paste, so a shell or Claude Code doesn't run it line by line). */
    var onPaste: (String) -> Unit = {}
    /** The keyboard's own paste command: paste from the clipboard. */
    var onPasteRequest: () -> Unit = {}
    var onStickyChanged: (ctrl: Boolean, alt: Boolean) -> Unit = { _, _ -> }
    var onHangulChanged: (Boolean) -> Unit = {}
    var settings: () -> Settings.Values = { Settings.Values() }

    val stickyCtrl get() = core.stickyCtrl
    val stickyAlt get() = core.stickyAlt
    /** In-app Hangul composition for hardware keys (only with Settings.appHangul). */
    var hangulMode = false
        private set

    private val hangul = Hangul()

    private val imm get() = context.getSystemService(InputMethodManager::class.java)
    private val core = InputCore(
        onBytes = { onBytes(it) },
        onComposing = { onComposing(it) },
        onSelection = { s -> imm.updateSelection(this, s[0], s[1], s[2], s[3]) },
        onStickyChanged = { c, a -> onStickyChanged(c, a) },
    )
    private val mirror get() = core.mirror
    /** Bumped for every new InputConnection: calls on an older one are ignored. */
    private var icGeneration = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, 0)
    }

    fun toggleCtrl() = core.toggleCtrl()
    fun toggleAlt() = core.toggleAlt()

    fun toggleHangul() {
        commitHangul()
        hangulMode = !hangulMode
        onHangulChanged(hangulMode)
    }

    private fun clearSticky() = core.clearSticky()

    private fun commitHangul() {
        val s = hangul.flush()
        onComposing("")
        if (s.isNotEmpty()) onBytes(s.toByteArray())
    }

    // ---------------------------------------------------------------- keyboard text buffer

    private fun emit(out: ImeMirror.Out) = core.emit(out)
    private fun afterImeEdit() = core.afterImeEdit()

    /**
     * Before input that bypasses the keyboard's text field (key events, key bar, paste):
     * sends what is on screen, the composition included, and starts a fresh buffer.
     */
    fun settle() = core.settle()

    // ---------------------------------------------------------------- hardware keys

    private fun isHangulToggle(e: KeyEvent): Boolean = when (settings().hangulToggle) {
        Settings.HangulToggle.SHIFT_SPACE -> e.keyCode == KeyEvent.KEYCODE_SPACE && e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed
        Settings.HangulToggle.CTRL_SPACE -> e.keyCode == KeyEvent.KEYCODE_SPACE && e.isCtrlPressed && !e.isShiftPressed
        Settings.HangulToggle.RIGHT_ALT -> e.keyCode == KeyEvent.KEYCODE_ALT_RIGHT
        Settings.HangulToggle.OFF -> false
    } || e.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || e.keyCode == KeyEvent.KEYCODE_KANA

    /** Hardware key down (or a key event the phone keyboard passed on). Returns true if consumed. */
    fun handleHardwareKey(e: KeyEvent): Boolean {
        val capsAsCtrl = settings().capsAsCtrl
        if (e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK && core.capsDown(capsAsCtrl)) return true
        val ctrl = core.ctrl(e.isCtrlPressed, capsAsCtrl)
        val alt = core.alt(e.isAltPressed)
        val plain = !ctrl && !alt && !e.isMetaPressed

        // ⌫ the keyboard didn't handle itself: delete from the buffer it sees, so it stays in step.
        if (e.keyCode == KeyEvent.KEYCODE_DEL && plain && !hangul.isComposing && !mirror.isEmpty) {
            val wasComposing = mirror.hasComposing
            if (mirror.backspace()) {
                if (wasComposing) settle() else afterImeEdit()
                return true
            }
        }
        if (!KeyEvent.isModifierKey(e.keyCode)) settle()

        val appHangul = settings().appHangul
        if (!appHangul && hangulMode) { commitHangul(); hangulMode = false; onHangulChanged(false) }
        if (appHangul && isHangulToggle(e)) { toggleHangul(); return true }
        // Shift+Space switches the phone keyboard's language; never type a stray space for it.
        if (!appHangul && e.keyCode == KeyEvent.KEYCODE_SPACE && e.isShiftPressed && plain) return true
        if (onShortcut(e)) return true

        if (hangulMode && plain) {
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
        if (e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) return core.capsUp(settings().capsAsCtrl)
        return false
    }

    // CapsLock's key-up never arrives if focus moves while it is held: it would stay Ctrl.
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) core.focusLost()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) core.focusLost()
    }

    // ---------------------------------------------------------------- key bar

    /** Key bar: special key by keycode (arrows, Tab, PgUp...). */
    fun sendKeyCode(keyCode: Int, shift: Boolean) {
        settle()
        if (hangul.isComposing) commitHangul()
        val s = KeyEncoder.special(keyCode, shift, stickyCtrl, stickyAlt, appCursor()) ?: return
        clearSticky()
        onBytes(s.toByteArray())
    }

    /** Key bar text. A single character honours sticky Ctrl/Alt. */
    fun sendText(t: String) {
        settle()
        if (t.isEmpty()) return
        if (hangul.isComposing) commitHangul()
        emit(ImeMirror.Out(0, t))
    }

    /** Raw bytes from the key bar (Esc, ^C, tmux prefix...). Clears sticky modifiers. */
    fun sendRaw(s: String) {
        settle()
        if (hangul.isComposing) commitHangul()
        clearSticky()
        onBytes(s.toByteArray())
    }

    // ---------------------------------------------------------------- InputConnection

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        // A new connection starts from an empty field. (No restartInput here: we are inside one.)
        core.newConnection()
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return MirrorConnection(++icGeneration)
    }

    private inner class MirrorConnection(private val gen: Int) : BaseInputConnection(this@TerminalInputView, false) {
        private var batch = 0
        private val live get() = gen == icGeneration

        /** Runs an edit from the keyboard; syncs once the outermost batch ends. */
        private inline fun edit(f: () -> Unit): Boolean {
            if (!live) return false
            f()
            if (batch == 0) afterImeEdit()
            return true
        }

        override fun beginBatchEdit(): Boolean {
            if (!live) return false
            batch++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (!live) return false
            if (batch > 0) batch--
            if (batch == 0) afterImeEdit()
            return batch > 0
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = if (live) mirror.textBeforeCursor(n) else null
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = if (live) mirror.textAfterCursor(n) else null
        override fun getSelectedText(flags: Int): CharSequence? = if (live) mirror.selectedText().ifEmpty { null } else null
        override fun getCursorCapsMode(reqModes: Int) = 0

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            if (!live) return null
            val t = mirror.text
            val a = minOf(mirror.selStart, mirror.selEnd); val b = maxOf(mirror.selStart, mirror.selEnd)
            val from = maxOf(0, a - beforeLength); val to = minOf(t.length, b + afterLength)
            return SurroundingText(t.substring(from, to), mirror.selStart - from, mirror.selEnd - from, from)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (!live) return null
            return ExtractedText().apply {
                text = mirror.text
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = mirror.selStart
                selectionEnd = mirror.selEnd
            }
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
            edit { mirror.setComposingText(text.toString(), newCursorPosition) }

        override fun setComposingRegion(start: Int, end: Int) = edit { mirror.setComposingRegion(start, end) }

        override fun finishComposingText() = edit { mirror.finishComposingText() }

        override fun setSelection(start: Int, end: Int) = edit { mirror.setSelection(start, end) }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (!live) return false
            val t = text.toString()
            if (t.contains('\n') || t.contains('\r')) {
                // Keyboards send Enter as a key event; a line break in committed text is either a
                // lone Enter or a paste (e.g. Gboard's clipboard).
                edit { mirror.finishComposingText() }
                settle()
                if (hangul.isComposing) commitHangul()
                if (t.length > 1) { clearSticky(); onPaste(t) } else onBytes(byteArrayOf('\r'.code.toByte()))
                return true
            }
            return edit { mirror.commitText(t, newCursorPosition) }
        }

        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit { mirror.replaceText(start, end, text.toString(), newCursorPosition) }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            var missing = 0
            // Keyboards that delete without looking at the buffer still delete in the terminal.
            return edit { missing = mirror.deleteSurroundingText(beforeLength, afterLength) }
                .also { if (it && missing > 0) onBytes(ByteArray(missing.coerceAtMost(64)) { 0x7f }) }
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            var missing = 0
            return edit { missing = mirror.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
                .also { if (it && missing > 0) onBytes(ByteArray(missing.coerceAtMost(64)) { 0x7f }) }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = live && super.sendKeyEvent(event)

        override fun performContextMenuAction(id: Int): Boolean {
            if (!live) return false
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                settle()
                onPasteRequest()
                return true
            }
            return super.performContextMenuAction(id)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            if (!live) return false
            settle()
            onBytes("\r".toByteArray())
            return true
        }

    }
}
