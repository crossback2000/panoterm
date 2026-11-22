package dev.panoterm.term

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyboard buffer → terminal with sticky Ctrl/Alt and CapsLock-as-Ctrl.
 * Regression tests for v1.3.13 / v1.3.14 (Hangul composition ended by a key, CapsLock stuck as Ctrl).
 */
class InputCoreTest {
    private val sent = mutableListOf<String>()
    private val selections = mutableListOf<List<Int>>()
    private var composing = ""
    private val core = InputCore(
        onBytes = { sent += String(it) },
        onComposing = { composing = it },
        onSelection = { selections += it.toList() },
        onStickyChanged = { _, _ -> },
    )
    private val m get() = core.mirror
    private val out get() = sent.joinToString("")

    /** A keyboard call, then the sync TerminalInputView runs after it (outside a batch). */
    private fun ime(f: ImeMirror.() -> Unit) { m.f(); core.afterImeEdit() }

    /** A key that bypasses the text field (hardware key, key bar), encoded like TerminalInputView does. */
    private fun key(c: Char, eventCtrl: Boolean = false, capsAsCtrl: Boolean = false) {
        core.settle()
        val ctrl = core.ctrl(eventCtrl, capsAsCtrl)
        val alt = core.alt(false)
        sent += String(KeyEncoder.encodeChar(c.code, ctrl, alt))
        core.clearSticky()
    }

    @Test fun compositionShownNotSent() {
        ime { setComposingText("ㅎ") }
        ime { setComposingText("한") }
        assertEquals("", out)
        assertEquals("한", composing)
    }

    @Test fun keyDuringCompositionSendsSyllableFirstAndTellsKeyboardItEnded() {
        // v1.3.13/14: "되요?" must not become "되?요", and the keyboard is told (via selection,
        // not restartInput) that nothing is composing any more.
        ime { commitText("되") }
        ime { setComposingText("요") }
        key('?')
        assertEquals("되요?", out)
        assertEquals("", composing)
        assertEquals(listOf(0, 0, -1, -1), selections.last())
        // Samsung Keyboard commits the orphaned syllable afterwards: not repeated.
        ime { commitText("요") }
        assertEquals("되요?", out)
    }

    @Test fun backspaceKeyDuringEnglishCompositionEditsTheComposition() {
        // v1.3.16: Samsung Keyboard composes English words and passes ⌫ on as a key event.
        // The word must stay composing (one letter shorter), not be sent to the terminal.
        ime { setComposingText("hello") }
        assertTrue(core.backspaceKey())
        assertEquals("", out)
        assertEquals("hell", composing)
        assertEquals(listOf(4, 4, 0, 4), selections.last())
        ime { setComposingText("help") }
        ime { commitText("help") }
        assertEquals("help", out)
    }

    @Test fun backspaceKeyDeletesCommittedText() {
        ime { commitText("abc") }
        assertTrue(core.backspaceKey())
        assertTrue(core.backspaceKey())
        assertEquals("abc\u007f\u007f", out)
        assertEquals(listOf(1, 1, -1, -1), selections.last())
    }

    @Test fun backspaceKeyOnEmptyBufferGoesToTheTerminal() {
        assertFalse(core.backspaceKey())
        ime { setComposingText("a") }
        assertTrue(core.backspaceKey())
        assertEquals("", composing)
        assertEquals("", out)
        // Buffer empty now: the next ⌫ is the terminal's.
        assertFalse(core.backspaceKey())
    }

    @Test fun enterDuringCompositionThenKeyboardFinishes() {
        ime { setComposingText("안") }
        core.settle(); sent += "\r"
        ime { finishComposingText() }
        assertEquals("안\r", out)
    }

    @Test fun stickyCtrlDoesNotApplyToTheFlushedSyllable() {
        // v1.3.14: Ctrl from the key bar was applied to the composing syllable instead of the key.
        ime { setComposingText("한") }
        core.toggleCtrl()
        key('c')
        assertEquals(listOf("한", "\u0003"), sent)
        assertFalse(core.stickyCtrl)
    }

    @Test fun stickyAltDoesNotApplyToTheFlushedSyllable() {
        ime { setComposingText("글") }
        core.toggleAlt()
        key('b')
        assertEquals(listOf("글", "\u001bb"), sent)
        assertFalse(core.stickyAlt)
    }

    @Test fun stickyCtrlAppliesToTheFirstCharacterTheKeyboardTypes() {
        core.toggleCtrl()
        ime { commitText("cd") }
        assertEquals(listOf("\u0003", "d"), sent)
        assertFalse(core.stickyCtrl)
        // The buffer no longer matches the terminal: it was reset.
        assertTrue(m.isClean)
    }

    @Test fun settleKeepsStickyForTheNextKey() {
        ime { commitText("x") }
        core.toggleCtrl()
        core.settle()
        assertTrue(core.stickyCtrl)
    }

    @Test fun newlineInBufferIsSentAsCarriageReturn() {
        ime { setComposingText("a\nb") }
        core.settle()
        assertEquals("a\rb", out)
    }

    @Test fun selectionIsReportedOnlyWhenItChanges() {
        ime { commitText("a") }
        val n = selections.size
        ime { setSelection(1, 1) }
        assertEquals(n, selections.size)
    }

    // ---------------------------------------------------------------- CapsLock as Ctrl

    @Test fun capsAsCtrlWhileHeld() {
        assertTrue(core.capsDown(capsAsCtrl = true))
        assertTrue(core.ctrl(false, capsAsCtrl = true))
        assertTrue(core.capsUp(capsAsCtrl = true))
        assertFalse(core.ctrl(false, capsAsCtrl = true))
    }

    @Test fun capsIsNotCtrlWhenTheSettingIsOff() {
        assertFalse(core.capsDown(capsAsCtrl = false))
        assertFalse(core.ctrl(false, capsAsCtrl = false))
        assertFalse(core.capsUp(capsAsCtrl = false))
    }

    @Test fun capsReleasedWhenFocusIsLost() {
        // v1.3.14: the key-up never arrived after switching apps, and every key became Ctrl.
        core.capsDown(capsAsCtrl = true)
        core.focusLost()
        assertFalse(core.ctrl(false, capsAsCtrl = true))
        key('a', capsAsCtrl = true)
        assertEquals("a", out)
    }

    @Test fun capsHeldDoesNotOutliveTheSetting() {
        core.capsDown(capsAsCtrl = true)
        // Setting turned off while held: not Ctrl, and the key-up is still consumed.
        assertFalse(core.ctrl(false, capsAsCtrl = false))
        assertTrue(core.capsUp(capsAsCtrl = false))
        core.capsDown(capsAsCtrl = true)
        core.capsUp(capsAsCtrl = false)
        assertFalse(core.ctrl(false, capsAsCtrl = true))
    }

    @Test fun eventCtrlAndStickyCtrl() {
        assertTrue(core.ctrl(true, false))
        core.toggleCtrl()
        assertTrue(core.ctrl(false, false))
        core.toggleCtrl()
        assertFalse(core.ctrl(false, false))
    }

    // ---------------------------------------------------------------- connection

    @Test fun newConnectionFlushesWhatIsLeft() {
        ime { setComposingText("한") }
        core.newConnection()
        assertEquals("한", out)
        assertEquals("", composing)
        assertTrue(m.isClean)
    }

    @Test fun emitReportsInSync() {
        assertTrue(core.emit(ImeMirror.Out(2, "ab")))
        assertArrayEquals(byteArrayOf(0x7f, 0x7f), sent[0].toByteArray())
        assertEquals("ab", sent[1])
    }
}
