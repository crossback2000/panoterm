package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Test

class PasteSanitizeTest {
    private fun s(t: String) = TermSession.sanitizePaste(t)

    @Test fun lineEndingsBecomeCr() {
        assertEquals("a\rb\rc", s("a\r\nb\nc"))
    }

    @Test fun keepsTabAndText() {
        assertEquals("한글\tok", s("한글\tok"))
    }

    @Test fun bracketedPasteEndCannotBreakOut() {
        assertEquals("[201~rm -rf ~\r", s("\u001b[201~rm -rf ~\n"))
    }

    @Test fun dropsOtherControls() {
        assertEquals("abc", s("a\u0003b\u007f\u009bc\u0000"))
    }
}
