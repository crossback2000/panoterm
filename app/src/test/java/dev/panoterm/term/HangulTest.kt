package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulTest {
    private fun type(keys: String): String {
        val h = Hangul()
        val sb = StringBuilder()
        keys.forEach { sb.append(h.key(it)) }
        return sb.append(h.flush()).toString()
    }

    @Test fun greeting() = assertEquals("안녕하세요", type("dkssudgktpdy"))
    @Test fun compoundFinalStays() = assertEquals("값", type("rkqt"))
    @Test fun compoundFinalThenVowelWithIeung() = assertEquals("값이", type("rkqtdl"))
    @Test fun compoundFinalSplitsOnVowel() = assertEquals("갑시", type("rkqtl"))
    @Test fun finalMovesToNextSyllable() = assertEquals("가나", type("rksk"))
    @Test fun compoundVowel() = assertEquals("와", type("dhk"))
    @Test fun doubleConsonantWithShift() = assertEquals("까", type("Rk"))
    @Test fun shiftedVowel() = assertEquals("얘", type("dO"))
    @Test fun loneConsonants() = assertEquals("ㅋㅋㅋ", type("zzz"))
    // ㅋㅡㄹ ㄹㅗ ㄷㅡ ㅋㅗ ㄷㅡ
    @Test fun sentence() = assertEquals("클로드코드", type("zmffhemzhem"))

    @Test fun backspaceRemovesLastJamo() {
        val h = Hangul()
        h.key('r'); h.key('k'); h.key('q') // 갑
        assertEquals("갑", h.composing)
        assertTrue(h.backspace())
        assertEquals("가", h.composing)
        assertTrue(h.backspace())
        assertEquals("ㄱ", h.composing)
        assertTrue(h.backspace())
        assertFalse(h.isComposing)
        assertFalse(h.backspace())
    }

    @Test fun backspaceSplitsCompoundVowel() {
        val h = Hangul()
        h.key('d'); h.key('h'); h.key('k') // 와
        assertTrue(h.backspace())
        assertEquals("오", h.composing)
    }
}
