package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TmuxSearchTest {
    private fun script(cmd: String) = cmd.removePrefix("sh -c '").removeSuffix("'")

    @Test fun anyQueryStaysInsideTheSingleQuotes() {
        val nasty = "it's \"\$(rm -rf ~)\" `x` \\ \$HOME ; | & 한글"
        for (op in TmuxSearch.Op.entries) {
            val cmd = TmuxSearch.command("main", op, nasty)
            assertTrue(cmd.startsWith("sh -c '") && cmd.endsWith("'"))
            assertFalse("$op", script(cmd).contains('\''))
            assertFalse("$op", script(cmd).contains("rm -rf"))
        }
    }

    @Test fun sessionNameIsSanitized() {
        assertTrue(TmuxSearch.command("a b;c", TmuxSearch.Op.OLDER, "x").contains("P=\"=a_b_c:\""))
    }

    @Test fun octalEncodesUtf8Bytes() {
        assertEquals("\\101\\047", TmuxSearch.octal("A'"))
        assertEquals("\\355\\225\\234", TmuxSearch.octal("한"))
    }

    @Test fun smartCase() {
        assertTrue(TmuxSearch.smartCaseFold("error"))
        assertFalse(TmuxSearch.smartCaseFold("Error"))
        assertTrue(TmuxSearch.command("m", TmuxSearch.Op.START, "error").contains("grep -c -i -F"))
        assertTrue(TmuxSearch.command("m", TmuxSearch.Op.START, "Error").contains("grep -c -F"))
    }

    @Test fun parseCount() {
        assertEquals(12, TmuxSearch.parseCount("N 12\n"))
        assertEquals(0, TmuxSearch.parseCount("N 0"))
        assertNull(TmuxSearch.parseCount("E\n"))
        assertNull(TmuxSearch.parseCount(null))
    }

    // ---- against a real tmux (skipped where there is none, e.g. CI) ----

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        p.waitFor(10, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText()
    }

    @Test fun searchesTmuxHistory() {
        assumeTrue(runCatching { sh("command -v tmux").isNotBlank() }.getOrDefault(false))
        val s = "panoterm_test_${System.nanoTime()}"
        ProcessBuilder(
            "tmux", "new", "-d", "-s", s, "-x", "80", "-y", "10",
            "for i in \$(seq 1 40); do echo row \$i 안녕 it\\'s; done; sleep 30",
        ).start().waitFor(10, TimeUnit.SECONDS)
        try {
            Thread.sleep(800)
            fun pos() = sh("tmux display -p -t =$s: \"#{pane_in_mode} #{e|-:#{e|+:#{history_size},#{copy_cursor_y}},#{scroll_position}}\"").trim()
            val q = "안녕 it's"
            assertEquals(40, TmuxSearch.parseCount(sh(TmuxSearch.command(s, TmuxSearch.Op.START, q))))
            val newest = pos()
            assertTrue(newest, newest.startsWith("1 "))
            sh(TmuxSearch.command(s, TmuxSearch.Op.OLDER, q))
            val older = pos()
            assertEquals(newest.substringAfter(' ').toInt() - 1, older.substringAfter(' ').toInt())
            sh(TmuxSearch.command(s, TmuxSearch.Op.NEWER, q))
            assertEquals(newest, pos())
            sh(TmuxSearch.command(s, TmuxSearch.Op.END, q))
            assertTrue(pos().startsWith("0 "))
            // No match: count 0, copy mode untouched.
            assertEquals(0, TmuxSearch.parseCount(sh(TmuxSearch.command(s, TmuxSearch.Op.START, "zzz"))))
            assertTrue(pos().startsWith("0 "))
        } finally {
            sh("tmux kill-session -t =$s")
        }
    }
}
