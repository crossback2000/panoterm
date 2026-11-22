package dev.panoterm.term

/**
 * The Android-free part of [TerminalInputView]: the keyboard's text buffer ([ImeMirror]) → terminal
 * bytes, sticky Ctrl/Alt from the key bar and CapsLock-as-Ctrl. Unit-tested (InputCoreTest).
 */
class InputCore(
    private val onBytes: (ByteArray) -> Unit,
    private val onComposing: (String) -> Unit,
    /** Tells the keyboard the selection and composition (selStart, selEnd, compStart, compEnd). */
    private val onSelection: (IntArray) -> Unit,
    private val onStickyChanged: (ctrl: Boolean, alt: Boolean) -> Unit,
) {
    val mirror = ImeMirror()

    var stickyCtrl = false
        private set
    var stickyAlt = false
        private set
    private var capsHeld = false

    /** Selection/composition last reported to the keyboard. */
    private var reported = IntArray(4)

    // ---------------------------------------------------------------- modifiers

    fun toggleCtrl() { stickyCtrl = !stickyCtrl; onStickyChanged(stickyCtrl, stickyAlt) }
    fun toggleAlt() { stickyAlt = !stickyAlt; onStickyChanged(stickyCtrl, stickyAlt) }

    fun clearSticky() {
        if (stickyCtrl || stickyAlt) {
            stickyCtrl = false; stickyAlt = false
            onStickyChanged(false, false)
        }
    }

    /** CapsLock down. Returns true if consumed (it is Ctrl). */
    fun capsDown(capsAsCtrl: Boolean): Boolean {
        if (!capsAsCtrl) return false
        capsHeld = true
        return true
    }

    /** CapsLock up. Consumed if it was held as Ctrl, even if the setting was turned off since. */
    fun capsUp(capsAsCtrl: Boolean): Boolean {
        val wasHeld = capsHeld
        capsHeld = false
        return wasHeld || capsAsCtrl
    }

    /** CapsLock's key-up never arrives if focus moves while it is held: it would stay Ctrl. */
    fun focusLost() { capsHeld = false }

    fun ctrl(eventCtrl: Boolean, capsAsCtrl: Boolean) = eventCtrl || (capsAsCtrl && capsHeld) || stickyCtrl
    fun alt(eventAlt: Boolean) = eventAlt || stickyAlt

    // ---------------------------------------------------------------- keyboard text buffer

    /** Sends a diff from the buffer. Returns false when the buffer no longer matches the terminal. */
    fun emit(out: ImeMirror.Out): Boolean {
        if (out.deletes > 0) onBytes(ByteArray(out.deletes) { 0x7f })
        if (out.text.isEmpty()) return true
        val s = lineBreaks(out.text)
        if (stickyCtrl || stickyAlt) {
            // Ctrl/Alt from the key bar applies to the first character typed.
            val cp = s.codePointAt(0)
            onBytes(KeyEncoder.encodeChar(cp, stickyCtrl, stickyAlt))
            clearSticky()
            val rest = s.substring(Character.charCount(cp))
            if (rest.isNotEmpty()) onBytes(rest.toByteArray())
            return false
        }
        onBytes(s.toByteArray())
        return true
    }

    /** After the keyboard edited the buffer: send what changed and show the composition. */
    fun afterImeEdit() {
        val inSync = emit(mirror.sync())
        if (!inSync || (!mirror.hasComposing && mirror.length > ImeMirror.MAX_LENGTH)) {
            resetMirror()
            return
        }
        onComposing(mirror.composing)
        reportSelection()
    }

    /**
     * Before input that bypasses the keyboard's text field (key events, key bar, paste):
     * sends what is on screen, the composition included, and starts a fresh buffer.
     * Sticky Ctrl/Alt is left for the key that follows: this text was typed before them.
     */
    fun settle() {
        if (mirror.isClean) return
        val out = mirror.flush()
        if (out.deletes > 0) onBytes(ByteArray(out.deletes) { 0x7f })
        if (out.text.isNotEmpty()) onBytes(lineBreaks(out.text).toByteArray())
        afterReset()
    }

    /** A new InputConnection starts from an empty field. */
    fun newConnection() {
        if (!mirror.isClean) { emit(mirror.flush()); onComposing("") }
        reported = intArrayOf(0, 0, -1, -1)
    }

    private fun resetMirror() {
        mirror.reset()
        afterReset()
    }

    /**
     * Tells the keyboard its field is empty and nothing is composing, as a text field cleared by
     * the app would. Never restartInput: Samsung Keyboard comes back from a restart in English.
     */
    private fun afterReset() {
        onComposing("")
        reportSelection()
    }

    private fun reportSelection() {
        val s = intArrayOf(mirror.selStart, mirror.selEnd, mirror.compStart, mirror.compEnd)
        if (s.contentEquals(reported)) return
        reported = s
        onSelection(s)
    }

    private fun lineBreaks(t: String) = t.replace("\r\n", "\r").replace('\n', '\r')
}
