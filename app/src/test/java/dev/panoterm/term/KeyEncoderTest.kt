package dev.panoterm.term

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** xterm byte sequences for special keys and Ctrl/Alt characters. */
class KeyEncoderTest {
    private fun sp(code: Int, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, app: Boolean = false) =
        KeyEncoder.special(code, shift, ctrl, alt, app)

    private fun ch(c: Char, ctrl: Boolean = false, alt: Boolean = false) = String(KeyEncoder.encodeChar(c.code, ctrl, alt))

    @Test fun enter() {
        assertEquals("\r", sp(KeyEvent.KEYCODE_ENTER))
        assertEquals("\r", sp(KeyEvent.KEYCODE_NUMPAD_ENTER))
        // Shift/Alt+Enter: newline in Claude Code's prompt instead of submitting.
        assertEquals("\u001b\r", sp(KeyEvent.KEYCODE_ENTER, shift = true))
        assertEquals("\u001b\r", sp(KeyEvent.KEYCODE_ENTER, alt = true))
    }

    @Test fun tabEscBackspace() {
        assertEquals("\t", sp(KeyEvent.KEYCODE_TAB))
        assertEquals("\u001b[Z", sp(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals("\u001b", sp(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("\u007f", sp(KeyEvent.KEYCODE_DEL))
        assertEquals("\u0017", sp(KeyEvent.KEYCODE_DEL, ctrl = true))
        assertEquals("\u001b\u007f", sp(KeyEvent.KEYCODE_DEL, alt = true))
        assertEquals("\u001b[3~", sp(KeyEvent.KEYCODE_FORWARD_DEL))
    }

    @Test fun arrowsNormalAppAndModified() {
        assertEquals("\u001b[A", sp(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001bOA", sp(KeyEvent.KEYCODE_DPAD_UP, app = true))
        assertEquals("\u001b[1;2B", sp(KeyEvent.KEYCODE_DPAD_DOWN, shift = true))
        assertEquals("\u001b[1;3C", sp(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true))
        assertEquals("\u001b[1;5D", sp(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true, app = true))
        assertEquals("\u001b[1;8A", sp(KeyEvent.KEYCODE_DPAD_UP, shift = true, alt = true, ctrl = true))
        assertEquals("\u001b[H", sp(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals("\u001bOF", sp(KeyEvent.KEYCODE_MOVE_END, app = true))
    }

    @Test fun pagingAndFunctionKeys() {
        assertEquals("\u001b[5~", sp(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals("\u001b[6;5~", sp(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true))
        assertEquals("\u001bOP", sp(KeyEvent.KEYCODE_F1))
        assertEquals("\u001b[1;2S", sp(KeyEvent.KEYCODE_F4, shift = true))
        assertEquals("\u001b[15~", sp(KeyEvent.KEYCODE_F5))
        assertEquals("\u001b[24~", sp(KeyEvent.KEYCODE_F12))
    }

    @Test fun characterKeysAreNotSpecial() {
        assertNull(sp(KeyEvent.KEYCODE_A))
        assertNull(sp(KeyEvent.KEYCODE_SPACE))
    }

    @Test fun ctrlCharacters() {
        assertEquals("\u0003", ch('c', ctrl = true))
        assertEquals("\u0003", ch('C', ctrl = true))
        assertEquals("\u0000", ch(' ', ctrl = true))
        assertEquals("\u001b", ch('[', ctrl = true))
        assertEquals("\u001f", ch('/', ctrl = true))
        assertEquals("\u007f", ch('?', ctrl = true))
        // No control code: the character itself.
        assertEquals("1", ch('1', ctrl = true))
        assertEquals("한", ch('한', ctrl = true))
    }

    @Test fun altPrefixesEsc() {
        assertEquals("\u001bb", ch('b', alt = true))
        assertEquals("\u001b\u0001", ch('a', ctrl = true, alt = true))
        assertEquals("\u001b한", ch('한', alt = true))
    }

    @Test fun plainCharacterAndSupplementaryCodePoint() {
        assertEquals("x", ch('x'))
        assertEquals("😀", String(KeyEncoder.encodeChar(0x1F600, ctrl = false, alt = false)))
    }
}
