package dev.panoterm.term

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCommandTest {
    private val cmd = TermSession.tmuxCommand("main")

    @Test fun wrappedInShSingleQuotes() {
        assertTrue(cmd.startsWith("exec sh -c '"))
        assertTrue(cmd.endsWith("'"))
        // The script itself must not contain single quotes (would break the wrapper).
        assertFalse(cmd.removePrefix("exec sh -c '").removeSuffix("'").contains('\''))
    }

    @Test fun looksInCommonLocations() {
        assertTrue(cmd.contains("/opt/homebrew/bin/tmux"))
        assertTrue(cmd.contains("-lc \"command -v tmux\""))
        assertTrue(cmd.contains("-u new -A -s main"))
        assertTrue(cmd.contains("777;panoterm;notmux"))
    }

    /** Writes the generated script to a file so it can be exercised with a real sh (see build docs). */
    @Test fun dumpScript() {
        val out = java.io.File(System.getProperty("java.io.tmpdir"), "panoterm-tmux-cmd.txt")
        out.writeText(cmd)
        assertTrue(out.length() > 0)
    }
}
