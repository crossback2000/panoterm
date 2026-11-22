package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays what different keyboards send and checks what reaches the terminal.
 * The fake terminal applies DEL (one character) and text, like a shell or Claude Code's prompt.
 */
class ImeMirrorTest {
    private val m = ImeMirror()
    private val term = StringBuilder()

    private fun apply(o: ImeMirror.Out) {
        repeat(o.deletes) { if (term.isNotEmpty()) term.setLength(term.offsetByCodePoints(term.length, -1)) }
        term.append(o.text)
    }

    /** One keyboard call followed by the sync TerminalInputView does after it. */
    private fun ime(f: ImeMirror.() -> Unit) { m.f(); apply(m.sync()) }
    private fun rawDel(n: Int) = apply(ImeMirror.Out(n, ""))
    /** A key that bypasses the text field (key event, key bar): flush, then the key itself. */
    private fun key(s: String) { apply(m.flush()); term.append(s) }

    private fun compose(vararg steps: String) = steps.forEach { s -> ime { setComposingText(s) } }

    @Test fun compositionIsNotSentUntilCommitted() {
        compose("ㅎ", "하", "한")
        assertEquals("", term.toString())
        assertEquals("한", m.composing)
        ime { commitText("한") }
        assertEquals("한", term.toString())
        assertEquals("", m.composing)
    }

    @Test fun samsungStyleHangulTyping() {
        // 한글: the keyboard commits 한 when ㄱ starts the next syllable.
        compose("ㅎ", "하", "한")
        ime { commitText("한"); setComposingText("ㄱ") }
        compose("그", "글")
        assertEquals("한", term.toString())
        assertEquals("글", m.composing)
    }

    @Test fun backspaceInsideCompositionSendsNothing() {
        compose("ㅎ", "하", "한")
        ime { commitText("한"); setComposingText("ㄱ") }
        compose("그", "글")
        // ⌫ ⌫ ⌫: the keyboard shrinks its composition.
        compose("그", "ㄱ", "")
        assertEquals("한", term.toString())
        assertFalse(m.hasComposing)
        // ⌫ once more: now a committed character goes.
        val missing = m.deleteSurroundingText(1, 0); apply(m.sync())
        assertEquals(0, missing)
        assertEquals("", term.toString())
    }

    @Test fun gboardReopensPreviousSyllable() {
        ime { commitText("한") }
        ime { commitText("글") }
        assertEquals("한글", term.toString())
        // ⌫: Gboard turns 글 back into a composition and shrinks it.
        ime { setComposingRegion(1, 2) }
        assertEquals("한", term.toString())
        compose("그")
        ime { commitText("그") }
        assertEquals("한그", term.toString())
    }

    @Test fun cheonjiinStrokes() {
        // 천지인: ㅇ, ㅣ → 이, ㆍ → 아
        compose("ㅇ", "이", "아")
        ime { finishComposingText() }
        assertEquals("아", term.toString())
    }

    @Test fun japaneseConversion() {
        compose("に", "にほ", "にほん", "日本")
        ime { commitText("日本") }
        assertEquals("日本", term.toString())
    }

    @Test fun punctuationKeyEventDoesNotOvertakeSyllable() {
        // "되요?": '?' arrives as a key event while 요 is still composing.
        ime { commitText("되") }
        compose("ㅇ", "요")
        key("?")
        assertEquals("되요?", term.toString())
        assertTrue(m.isEmpty)
    }

    /** A character key typed into the buffer while composing ([ImeMirror.typeKey]). */
    private fun typeKey(s: String) = ime { typeKey(s) }

    @Test fun characterKeyDuringCompositionKeepsTheKeyboard() {
        // "없니? 어": '?' arrives as a key event while 니 is composing. No restart, no repeat.
        ime { commitText("없") }
        compose("ㄴ", "니")
        typeKey("?")
        assertEquals("없니?", term.toString())
        assertEquals("없니?", m.text)
        // The keyboard commits the syllable afterwards (either way), then types on.
        ime { finishComposingText() }
        ime { commitText(" ") }
        compose("ㅇ", "어")
        assertEquals("없니? ", term.toString())
        ime { commitText("어") }
        assertEquals("없니? 어", term.toString())
    }

    @Test fun keyboardCommittingTheSyllableAfterTheKeyIsNotRepeated() {
        compose("ㄴ", "니")
        typeKey("?")
        ime { commitText("니") }
        assertEquals("니?", term.toString())
        assertEquals("니?", m.text)
        assertEquals(2, m.selStart) // the cursor stays after '?', where the terminal's is
        ime { commitText(" ") }
        assertEquals("니? ", term.toString())
    }

    @Test fun keyboardChangingTheSyllableAfterTheKeyIsCorrected() {
        compose("ㄴ", "니")
        typeKey("!")
        compose("닉")
        ime { finishComposingText() }
        assertEquals("닉!", term.toString())
        ime { commitText("a") }
        assertEquals("닉!a", term.toString())
    }

    @Test fun deleteKeyWithEmptyBufferIsNotHandledHere() {
        assertFalse(m.backspace())
    }

    @Test fun deleteKeyDuringCompositionShrinksIt() {
        // The keyboard passed ⌫ on as a key event even though it was composing.
        ime { commitText("가") }
        compose("나", "난")
        assertTrue(m.backspace())
        apply(m.sync())
        assertFalse(m.hasComposing)
        assertEquals("가", term.toString())
    }

    @Test fun deleteKeyAfterCommittedText() {
        ime { commitText("abc") }
        assertTrue(m.backspace()); apply(m.sync())
        assertEquals("ab", term.toString())
    }

    @Test fun blindDeleteBeyondBufferIsReported() {
        // Keyboards that delete without looking: the terminal still gets the DEL.
        term.append("xyz")
        val missing = m.deleteSurroundingText(1, 0); apply(m.sync()); rawDel(missing)
        assertEquals(1, missing)
        assertEquals("xy", term.toString())
    }

    @Test fun deleteSkipsCompositionAndRemovesTextBeforeIt() {
        ime { commitText("a") }
        compose("한")
        ime { deleteSurroundingText(1, 0) }
        assertEquals("", term.toString())
        assertEquals("한", m.composing)
    }

    @Test fun editInTheMiddleRetypesTheTail() {
        ime { commitText("abc") }
        ime { setSelection(1, 1) }
        ime { commitText("X") }
        assertEquals("aXbc", term.toString())
    }

    @Test fun emojiIsDeletedAsOneCharacter() {
        ime { commitText("a😀") }
        val missing = m.deleteSurroundingTextInCodePoints(1, 0); apply(m.sync())
        assertEquals(0, missing)
        assertEquals("a", term.toString())
    }

    @Test fun replacingEmojiDoesNotSplitIt() {
        ime { commitText("😀") }
        ime { setSelection(0, 2) }
        ime { commitText("😁") } // same high surrogate, different low one
        assertEquals("😁", term.toString())
    }

    @Test fun cursorPositionRules() {
        m.setComposingText("abc", 1)
        assertEquals(3, m.selStart)
        m.commitText("abc", 0) // cursor before the inserted text
        assertEquals(0, m.selStart)
        assertEquals("abc", m.text)
    }

    @Test fun flushAfterCommitSendsOnlyTheRest() {
        ime { commitText("해") }
        compose("줘")
        key("\r")
        assertEquals("해줘\r", term.toString())
        // A new composition after the flush starts from an empty buffer.
        compose("요")
        ime { commitText("요") }
        assertEquals("해줘\r요", term.toString())
    }

    @Test fun flushSendsDeletesStillPendingInABatch() {
        ime { commitText("ab") }
        // Inside a batch the keyboard deleted everything but no sync has happened yet.
        m.deleteSurroundingText(2, 0)
        assertTrue(m.isEmpty)
        assertFalse(m.isClean)
        key("\r")
        assertEquals("\r", term.toString())
        assertTrue(m.isClean)
    }

    @Test fun textBeforeCursorShowsTheComposition() {
        ime { commitText("가") }
        compose("나")
        assertEquals("가나", m.textBeforeCursor(10))
        assertEquals("나", m.textBeforeCursor(1))
    }
}
