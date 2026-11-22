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

    @Test fun installerIsQuotedSafelyAndCoversPackageManagers() {
        val inst = TermSession.installTmuxCommand("main")
        assertTrue(inst.startsWith("exec sh -c '") && inst.endsWith("'"))
        assertFalse(inst.removePrefix("exec sh -c '").removeSuffix("'").contains('\''))
        listOf("apt-get", "dnf", "yum", "pacman", "zypper", "apk add", "brew", "pkg install").forEach {
            assertTrue("missing $it", inst.contains(it))
        }
        // After installing it attaches to tmux the same way as a normal connect.
        assertTrue(inst.contains("-u new -A -s main"))
    }

    /** Writes the generated scripts to files so they can be exercised with a real sh. */
    @Test fun dumpScript() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir")!!)
        java.io.File(dir, "panoterm-tmux-cmd.txt").writeText(cmd)
        java.io.File(dir, "panoterm-tmux-install.txt").writeText(TermSession.installTmuxCommand("main"))
    }
}
