package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeCompositionTest {
    private val out = StringBuilder()
    private var shown = ""
    private val ime = ImeComposition(send = { out.append(it) }, show = { shown = it })

    /** A key that bypasses the InputConnection (IME key event or key-bar button). */
    private fun key(s: String) { ime.flush(); out.append(s) }

    @Test fun punctuationKeyEventDoesNotOvertakeSyllable() {
        // "되요?" — the IME sends '?' as a key event while 요 is still composing,
        // then finishes the composition.
        ime.setComposing("되"); ime.commit("되")
        ime.setComposing("ㅇ"); ime.setComposing("요")
        key("?")
        ime.finish()
        assertEquals("되요?", out.toString())
        assertEquals("", shown)
    }

    @Test fun imeCommittingFlushedSyllableIsNotDuplicated() {
        ime.setComposing("요")
        key(" ")
        ime.commit("요") // some IMEs commit after the key event
        assertEquals("요 ", out.toString())
    }

    @Test fun commitWithMoreThanFlushedSendsOnlyTheRest() {
        ime.setComposing("요")
        key("?")
        ime.commit("요!")
        assertEquals("요?!", out.toString())
    }

    @Test fun newCompositionAfterFlushIsSentNormally() {
        ime.setComposing("요")
        key(" ")
        ime.setComposing("요") // next word starts with the same syllable
        ime.commit("요")
        assertEquals("요 요", out.toString())
    }

    @Test fun enterAfterComposition() {
        ime.setComposing("해"); ime.commit("해")
        ime.setComposing("줘")
        key("\r")
        assertEquals("해줘\r", out.toString())
    }

    @Test fun flushWithoutCompositionDoesNothing() {
        key("a")
        ime.finish()
        assertEquals("a", out.toString())
    }
}
