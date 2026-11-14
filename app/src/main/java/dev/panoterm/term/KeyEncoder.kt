package dev.panoterm.term

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** Translates key presses (hardware keyboard, key bar) into xterm byte sequences. */
object KeyEncoder {
    private const val ESC = "\u001b"

    /** Special (non-printable) keys. Returns null for keys that produce characters. */
    fun special(keyCode: Int, shift: Boolean, ctrl: Boolean, alt: Boolean, appCursor: Boolean): String? {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun cursor(c: Char) = when {
            mod > 1 -> "$ESC[1;$mod$c"
            appCursor -> "${ESC}O$c"
            else -> "$ESC[$c"
        }
        fun tilde(n: Int) = if (mod > 1) "$ESC[$n;$mod~" else "$ESC[$n~"
        fun ss3(c: Char) = if (mod > 1) "$ESC[1;$mod$c" else "${ESC}O$c"

        return when (keyCode) {
            // Shift/Alt+Enter = ESC CR: inserts a newline in Claude Code's prompt instead of submitting.
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (shift || alt) "$ESC\r" else "\r"
            KeyEvent.KEYCODE_TAB -> if (shift) "$ESC[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> ESC
            KeyEvent.KEYCODE_DEL -> when {
                ctrl -> "\u0017" // ^W: delete previous word
                alt -> "$ESC\u007f"
                else -> "\u007f"
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3)
            KeyEvent.KEYCODE_INSERT -> tilde(2)
            KeyEvent.KEYCODE_DPAD_UP -> cursor('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> cursor('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursor('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> cursor('D')
            KeyEvent.KEYCODE_MOVE_HOME -> cursor('H')
            KeyEvent.KEYCODE_MOVE_END -> cursor('F')
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6)
            KeyEvent.KEYCODE_F1 -> ss3('P')
            KeyEvent.KEYCODE_F2 -> ss3('Q')
            KeyEvent.KEYCODE_F3 -> ss3('R')
            KeyEvent.KEYCODE_F4 -> ss3('S')
            KeyEvent.KEYCODE_F5 -> tilde(15)
            KeyEvent.KEYCODE_F6 -> tilde(17)
            KeyEvent.KEYCODE_F7 -> tilde(18)
            KeyEvent.KEYCODE_F8 -> tilde(19)
            KeyEvent.KEYCODE_F9 -> tilde(20)
            KeyEvent.KEYCODE_F10 -> tilde(21)
            KeyEvent.KEYCODE_F11 -> tilde(23)
            KeyEvent.KEYCODE_F12 -> tilde(24)
            else -> null
        }
    }

    /** Full hardware key event → bytes, or null if the key should be left to the system. */
    fun encode(e: KeyEvent, appCursor: Boolean, stickyCtrl: Boolean = false, stickyAlt: Boolean = false): ByteArray? {
        val ctrl = e.isCtrlPressed || stickyCtrl
        val alt = e.isAltPressed || stickyAlt
        special(e.keyCode, e.isShiftPressed, ctrl, alt, appCursor)?.let { return it.toByteArray() }

        val meta = e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val uc = e.getUnicodeChar(meta)
        if (uc == 0 || (uc and KeyCharacterMap.COMBINING_ACCENT) != 0) return null
        return encodeChar(uc, ctrl, alt)
    }

    fun encodeChar(codePoint: Int, ctrl: Boolean, alt: Boolean): ByteArray {
        val cp = if (ctrl) ctrlCode(codePoint) ?: codePoint else codePoint
        val body = String(Character.toChars(cp)).toByteArray()
        return if (alt) byteArrayOf(0x1b) + body else body
    }

    private fun ctrlCode(c: Int): Int? = when (c) {
        in 'a'.code..'z'.code -> c - 'a'.code + 1
        in 'A'.code..'Z'.code -> c - 'A'.code + 1
        '@'.code, ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '/'.code, '-'.code, '7'.code -> 31
        '?'.code, '8'.code -> 127
        else -> null
    }
}
