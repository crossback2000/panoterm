package dev.panoterm.term

/**
 * The soft keyboard's view of the terminal input line.
 *
 * A terminal can't be edited in place, and every keyboard composes text its own way (Samsung,
 * Gboard, 천지인, Japanese kana, swipe ...). Instead of interpreting each keyboard's calls, the
 * keyboard gets a real text field: this buffer, which it edits exactly like any other text field.
 * After each edit the committed part of the buffer (everything outside the composing region) is
 * compared with what the terminal already received, and only the difference goes out: removed
 * characters as DEL, new ones as text. The terminal's cursor stays at the end of what was sent,
 * so even an edit in the middle of the buffer comes out right (the tail is deleted and retyped).
 *
 * The composing region (한 → 하 → ㅎ while typing or deleting) is never sent; it is only shown.
 * Input that doesn't go through the keyboard's text field (key events, key bar, Enter) must call
 * [flush] first, so a syllable still being composed reaches the terminal before that input.
 *
 * Pure logic, no Android types, so keyboard behaviour can be replayed in unit tests.
 */
class ImeMirror {
    /** What to send: [deletes] DELs, then [text]. */
    data class Out(val deletes: Int, val text: String) {
        val isEmpty get() = deletes == 0 && text.isEmpty()
    }

    private val buf = StringBuilder()
    /** Committed text the terminal has received since the last [reset]. */
    private var sent = ""

    var selStart = 0; private set
    var selEnd = 0; private set
    /** Composing region, -1 when there is none. Never empty. */
    var compStart = -1; private set
    var compEnd = -1; private set
    /**
     * A composition [flush] took from the keyboard. Keyboards may still commit it
     * afterwards (some do after the key event that ended it); that commit is already here.
     */
    private var orphan: String? = null

    val text: String get() = buf.toString()
    val length: Int get() = buf.length
    val isEmpty: Boolean get() = buf.isEmpty()
    /** Nothing in the buffer and nothing sent from it: [flush] would do nothing. */
    val isClean: Boolean get() = buf.isEmpty() && sent.isEmpty()
    val hasComposing: Boolean get() = compStart >= 0
    val composing: String get() = if (hasComposing) buf.substring(compStart, compEnd) else ""

    // ---------------------------------------------------------------- reading (for the IME)

    private val selMin get() = minOf(selStart, selEnd)
    private val selMax get() = maxOf(selStart, selEnd)

    fun textBeforeCursor(n: Int): String = buf.substring(maxOf(0, selMin - n.coerceAtLeast(0)), selMin)
    fun textAfterCursor(n: Int): String = buf.substring(selMax, minOf(buf.length, selMax + n.coerceAtLeast(0)))
    fun selectedText(): String = buf.substring(selMin, selMax)

    // ---------------------------------------------------------------- editing (InputConnection semantics)

    /** Replaces the composing region (or the selection) with [t] and makes it the composing region. */
    fun setComposingText(t: String, newCursorPosition: Int = 1) {
        orphan = null
        val (a, b) = replaceRange()
        buf.replace(a, b, t)
        setComposing(a, a + t.length)
        placeCursor(a, t.length, newCursorPosition)
    }

    /** Replaces the composing region (or the selection) with [t] and ends the composition. */
    fun commitText(t: String, newCursorPosition: Int = 1) {
        if (!hasComposing && t == orphan) { orphan = null; return }
        orphan = null
        val (a, b) = replaceRange()
        buf.replace(a, b, t)
        clearComposing()
        placeCursor(a, t.length, newCursorPosition)
    }

    fun finishComposingText() = clearComposing()

    /** Marks existing text as composing (Gboard does this to reopen the previous syllable). */
    fun setComposingRegion(start: Int, end: Int) {
        orphan = null
        val a = start.coerceIn(0, buf.length); val b = end.coerceIn(0, buf.length)
        setComposing(minOf(a, b), maxOf(a, b))
    }

    fun setSelection(start: Int, end: Int) {
        selStart = start.coerceIn(0, buf.length); selEnd = end.coerceIn(0, buf.length)
    }

    /**
     * Deletes around the selection, skipping the composing region (like the platform's
     * BaseInputConnection). Returns how many characters were asked for before the start of the
     * buffer: some keyboards delete without looking, and those must still reach the terminal.
     */
    fun deleteSurroundingText(before: Int, after: Int): Int {
        orphan = null
        var a = selMin; var b = selMax
        if (hasComposing) { a = minOf(a, compStart); b = maxOf(b, compEnd) }
        val wantBefore = before.coerceAtLeast(0)
        val delBefore = minOf(wantBefore, a)
        val delAfter = minOf(after.coerceAtLeast(0), buf.length - b)
        delete(b, b + delAfter)
        delete(a - delBefore, a)
        return wantBefore - delBefore
    }

    /** Same as [deleteSurroundingText], counted in code points (emoji are two chars). */
    fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Int {
        orphan = null
        var a = selMin; var b = selMax
        if (hasComposing) { a = minOf(a, compStart); b = maxOf(b, compEnd) }
        var from = a; var cps = 0
        while (cps < before && from > 0) { from -= Character.charCount(buf.codePointBefore(from)); cps++ }
        val missing = before.coerceAtLeast(0) - cps
        var to = b; var cpa = 0
        while (cpa < after && to < buf.length) { to += Character.charCount(buf.codePointAt(to)); cpa++ }
        delete(b, to)
        delete(from, a)
        return missing
    }

    /** Replaces [start, end) with [t] (InputConnection.replaceText). */
    fun replaceText(start: Int, end: Int, t: String, newCursorPosition: Int = 1) {
        orphan = null
        val a = minOf(start, end).coerceIn(0, buf.length); val b = maxOf(start, end).coerceIn(0, buf.length)
        selStart = a; selEnd = b
        clearComposing()
        commitText(t, newCursorPosition)
    }

    /**
     * A ⌫ key the keyboard didn't handle itself. Deletes the selection or one code point before
     * the cursor (from the composition if that is where the cursor is). Returns false when there
     * is nothing in the buffer to delete, so the DEL should go straight to the terminal.
     */
    fun backspace(): Boolean {
        orphan = null
        if (selMin != selMax) { delete(selMin, selMax); return true }
        if (selMin == 0) return false
        delete(selMin - Character.charCount(buf.codePointBefore(selMin)), selMin)
        return true
    }

    // ---------------------------------------------------------------- syncing with the terminal

    /** Brings the terminal up to date with the committed text. */
    fun sync(): Out = diffTo(committed())

    /**
     * Sends everything, the composition included, and empties the buffer: for input that bypasses
     * the keyboard's text field and must come after what is on screen.
     */
    fun flush(): Out {
        // The keyboard learns from the selection update that its composition ended (as when a
        // text field is cleared). If it commits it anyway, that commit is dropped.
        if (hasComposing) orphan = composing
        val out = diffTo(buf.toString())
        reset()
        return out
    }

    /** Forgets the buffer (the terminal line changed by other means). */
    fun reset() {
        buf.setLength(0); sent = ""
        selStart = 0; selEnd = 0
        clearComposing()
    }

    private fun committed(): String =
        if (hasComposing) buf.substring(0, compStart) + buf.substring(compEnd) else buf.toString()

    private fun diffTo(target: String): Out {
        var p = 0
        val max = minOf(sent.length, target.length)
        while (p < max && sent[p] == target[p]) p++
        // Never split a surrogate pair: the terminal deletes whole characters.
        if (p > 0 && Character.isHighSurrogate(target[p - 1])) p--
        val out = Out(sent.codePointCount(p, sent.length), target.substring(p))
        sent = target
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun replaceRange(): Pair<Int, Int> =
        if (hasComposing) compStart to compEnd else selMin to selMax

    private fun setComposing(a: Int, b: Int) {
        if (a < b) { compStart = a; compEnd = b } else clearComposing()
    }

    private fun clearComposing() { compStart = -1; compEnd = -1 }

    /** newCursorPosition as defined by InputConnection.setComposingText / commitText. */
    private fun placeCursor(start: Int, len: Int, newCursorPosition: Int) {
        val c = if (newCursorPosition > 0) start + len + newCursorPosition - 1 else start + newCursorPosition
        selStart = c.coerceIn(0, buf.length); selEnd = selStart
    }

    /** Deletes [a, b), moving the selection and composing region like text spans would move. */
    private fun delete(a: Int, b: Int) {
        if (a >= b) return
        fun shift(p: Int) = when { p >= b -> p - (b - a); p > a -> a; else -> p }
        buf.delete(a, b)
        selStart = shift(selStart); selEnd = shift(selEnd)
        if (hasComposing) setComposing(shift(compStart), shift(compEnd))
    }

    companion object {
        /** Past this the buffer is dropped once nothing is composing (a keyboard needs little context). */
        const val MAX_LENGTH = 1000
    }
}
