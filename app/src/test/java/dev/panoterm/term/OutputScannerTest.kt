package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class OutputScannerTest {
    private val bells = mutableListOf<Unit>()
    private val notes = mutableListOf<Pair<String, String>>()
    private val clips = mutableListOf<String>()
    private val s = OutputScanner({ bells.add(Unit) }, { t, b -> notes.add(t to b) }, { clips.add(it) })

    @Test fun bareBell() {
        s.feed("hello\u0007".toByteArray())
        assertEquals(1, bells.size)
    }

    @Test fun belTerminatingOscIsNotABell() {
        s.feed("\u001b]0;window title\u0007".toByteArray())
        assertEquals(0, bells.size)
    }

    @Test fun osc9Notification() {
        s.feed("\u001b]9;Claude needs your input\u0007".toByteArray())
        assertEquals(listOf("" to "Claude needs your input"), notes)
    }

    @Test fun osc9ProgressIgnored() {
        s.feed("\u001b]9;4;1;50\u001b\\".toByteArray())
        assertEquals(0, notes.size)
    }

    @Test fun osc777NotifyWithStTerminator() {
        s.feed("\u001b]777;notify;빌드;완료되었습니다\u001b\\".toByteArray())
        assertEquals(listOf("빌드" to "완료되었습니다"), notes)
    }

    @Test fun osc52Clipboard() {
        val b64 = Base64.getEncoder().encodeToString("복사된 텍스트".toByteArray())
        s.feed("\u001b]52;c;$b64\u0007".toByteArray())
        assertEquals(listOf("복사된 텍스트"), clips)
    }

    @Test fun noTmuxSignal() {
        var hit = 0
        val sc = OutputScanner({}, { _, _ -> }, {}, { hit++ })
        sc.feed("\u001b]777;panoterm;notmux\u0007[PanoTerm] ...".toByteArray())
        assertEquals(1, hit)
    }

    @Test fun sequenceSplitAcrossChunks() {
        s.feed("\u001b]9;par".toByteArray())
        s.feed("tial\u0007".toByteArray())
        assertEquals(listOf("" to "partial"), notes)
    }
}
