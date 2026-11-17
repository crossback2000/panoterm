package dev.panoterm.term

/**
 * Soft-keyboard (IME) composition for a terminal, which can't show uncommitted text inline.
 *
 * The syllable being typed is held (and shown via [show]) until the IME finishes it. Keys the
 * IME delivers as key events (space, ?, Enter ...) and key-bar buttons bypass the
 * InputConnection, so they used to overtake the held syllable ("되요?" arrived as "되?요").
 * Callers must call [flush] before sending such input. If the IME later commits the syllable
 * itself, the part already sent is dropped.
 */
class ImeComposition(
    private val send: (String) -> Unit,
    private val show: (String) -> Unit,
    /** For [flush]: plain text, so a pending sticky Ctrl/Alt stays for the key that follows. */
    private val sendPlain: (String) -> Unit = send,
) {
    var composing = ""
        private set

    /** Text sent by [flush] that the IME may still commit itself afterwards. */
    private var flushed = ""

    val isComposing: Boolean get() = composing.isNotEmpty()

    /** InputConnection.setComposingText */
    fun setComposing(text: String) {
        flushed = ""
        composing = text
        show(text)
    }

    /** InputConnection.finishComposingText */
    fun finish() {
        flushed = ""
        if (composing.isEmpty()) return
        val c = composing
        composing = ""
        show("")
        send(c)
    }

    /** InputConnection.commitText: replaces the composing text. */
    fun commit(text: String) {
        val f = flushed
        flushed = ""
        if (composing.isNotEmpty()) { composing = ""; show("") }
        val out = if (f.isNotEmpty() && text.startsWith(f)) text.substring(f.length) else text
        if (out.isNotEmpty()) send(out)
    }

    /** Before any input that doesn't come through the InputConnection. */
    fun flush() {
        if (composing.isEmpty()) return
        val c = composing
        composing = ""
        flushed = c
        show("")
        sendPlain(c)
    }
}
