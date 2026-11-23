package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionTest {
    @Test fun claudeSlashInBox() =
        assertEquals(CompletionContext.ClaudeSlash("/co"), Completion.parse("│ > /co"))

    @Test fun claudeSlashPlain() =
        assertEquals(CompletionContext.ClaudeSlash("/"), Completion.parse("> /"))

    @Test fun claudeAtFile() =
        assertEquals(CompletionContext.ClaudeFile("src/ma"), Completion.parse("│ > look at @src/ma"))

    // Claude Code 2.x: "❯ /co" between ─── rules (the page tells us it's Claude's box).
    @Test fun claudeChevronSlash() =
        assertEquals(CompletionContext.ClaudeSlash("/co"), Completion.parse("❯ /co", inClaudeBox = true))

    @Test fun claudeChevronAtFile() =
        assertEquals(CompletionContext.ClaudeFile("src/ma"), Completion.parse("❯ look at @src/ma", inClaudeBox = true))

    // It used to fall through to the shell parser and list the root folder for "/co".
    @Test fun claudeBoxNeverShell() = assertNull(Completion.parse("❯ fix /usr", inClaudeBox = true))

    @Test fun slashUsesClaudeMenuFirst() {
        val menu = listOf("/copy" to "Copy the last response", "/color" to "Set the prompt bar color", "/simplify" to "Review changed code")
        val s = Completion.slashSuggestions("/co", menu, ClaudeCommands.korean)
        assertEquals(listOf("/copy", "/color"), s.take(2).map { it.label })
        assertTrue(s.any { it.label == "/compact" })
        assertFalse(s.any { it.label == "/simplify" })
        assertEquals("py", s[0].insert)
        assertEquals("마지막 답 복사", s[0].hint)
    }

    @Test fun slashFuzzyMenuEntryRetypes() {
        val s = Completion.slashSuggestions("/cpt", listOf("/compact" to "Free up context"), emptyList())
        assertEquals("\u007f\u007f\u007f\u007f/compact", s.single().insert)
    }

    @Test fun claudePlainTextHasNoCompletion() = assertNull(Completion.parse("│ > fix the bug"))

    @Test fun shellCdDirsOnly() =
        assertEquals(CompletionContext.ShellPath("ser", true), Completion.parse("me@box:~/proj$ cd ser"))

    @Test fun shellArgPath() =
        assertEquals(CompletionContext.ShellPath("src/ma", false), Completion.parse("me@box:~/proj$ vim src/ma"))

    @Test fun shellCommandName() =
        assertEquals(CompletionContext.ShellCommand("gi"), Completion.parse("me@box:~$ gi"))

    @Test fun zshPercentPrompt() =
        assertEquals(CompletionContext.ShellPath("Doc", true), Completion.parse("me@mac ~ % cd Doc"))

    @Test fun starshipPrompt() =
        assertEquals(CompletionContext.ShellPath("se", true), Completion.parse("proj on  main ❯ cd se"))

    @Test fun nothingAfterSpace() = assertNull(Completion.parse("me@box:~$ cd "))
    @Test fun noPromptNoCompletion() = assertNull(Completion.parse("some program output"))

    @Test fun cwdFromPrompt() {
        assertEquals("~/proj/app", Completion.cwdFromPrompt("me@box:~/proj/app$ cd ser"))
        assertEquals("/var/log", Completion.cwdFromPrompt("root@box:/var/log# ls"))
        assertEquals("", Completion.cwdFromPrompt("❯ cd ser"))
    }

    @Test fun pathFilteringDirsFirst() {
        val ls = listOf("service/", "server-logs/", "setup.sh", "src/", ".secret/", "README.md")
        val s = Completion.pathSuggestions(ls, "se", dirsOnly = false)
        assertEquals(listOf("server-logs/", "service/", "setup.sh"), s.map { it.label })
        assertEquals("rvice/", s.first { it.label == "service/" }.insert)   // folders: keep going
        assertEquals("tup.sh ", s.first { it.label == "setup.sh" }.insert) // files: add a space
    }

    @Test fun cdOnlyOffersDirs() {
        val s = Completion.pathSuggestions(listOf("service/", "setup.sh"), "se", dirsOnly = true)
        assertEquals(listOf("service/"), s.map { it.label })
    }

    @Test fun subdirToken() {
        val s = Completion.pathSuggestions(listOf("main.kt", "model/"), "src/ma", dirsOnly = false)
        assertEquals(listOf("main.kt"), s.map { it.label })
        assertEquals("in.kt ", s[0].insert)
    }

    @Test fun hiddenOnlyWhenAskedFor() {
        val ls = listOf(".config/", ".bashrc", "code/")
        assertFalse(Completion.pathSuggestions(ls, "", false).any { it.label.startsWith(".") })
        assertTrue(Completion.pathSuggestions(ls, ".c", false).any { it.label == ".config/" })
    }

    @Test fun quotingIsSafe() {
        assertEquals("'a'\\''b'", Completion.shq("a'b"))
        val cmd = Completion.listingCommand("main", "~/x'y", "src/")
        assertTrue(cmd.startsWith("sh -c '"))
        assertTrue(cmd.contains("ls -1Ap"))
    }

    /** Dumps a listing command for an sh smoke test (see build docs). */
    @Test fun dumpListingCommand() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir")!!)
        java.io.File(dir, "panoterm-listing-cmd.txt").writeText(Completion.listingCommand(null, "~/proj", ""))
        java.io.File(dir, "panoterm-listing-sub.txt").writeText(Completion.listingCommand(null, "", "~/proj/se"))
    }
}
