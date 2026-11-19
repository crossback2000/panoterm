package dev.panoterm.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {
    private var t = 10_000L
    private val tracker = ActivityTracker { t }

    @Test fun steadyOutputIsWorking() {
        assertEquals(AgentState.IDLE, tracker.state())
        repeat(10) { tracker.onOutput(); t += 100 }
        assertEquals(AgentState.WORKING, tracker.state())
        t += 5000
        assertEquals(AgentState.IDLE, tracker.state())
    }

    @Test fun echoAndOccasionalRedrawsAreNotWork() {
        // Typing: each key echoes right after the input.
        repeat(20) { tracker.onInput(); t += 50; tracker.onOutput(); t += 100 }
        assertEquals(AgentState.IDLE, tracker.state())
        // A status clock once a second.
        t += 2000
        repeat(5) { tracker.onOutput(); t += 1000 }
        assertEquals(AgentState.IDLE, tracker.state())
    }

    @Test fun promptAndAttentionAreWaiting() {
        tracker.promptOnScreen = true
        assertEquals(AgentState.WAITING, tracker.state())
        tracker.promptOnScreen = false
        tracker.onAttention()
        assertEquals(AgentState.WAITING, tracker.state())
        tracker.onInput()
        assertEquals(AgentState.IDLE, tracker.state())
        tracker.onAttention()
        tracker.seen()
        assertEquals(AgentState.IDLE, tracker.state())
    }

    @Test fun maxStatePrefersWaiting() {
        assertEquals(AgentState.WAITING, maxState(AgentState.WORKING, AgentState.WAITING))
        assertEquals(AgentState.WORKING, maxState(AgentState.WORKING, AgentState.IDLE))
    }

    private val approval = listOf(
        "╭──────────────────────────────╮",
        "│ Bash command                 │",
        "│ Do you want to proceed?      │",
        "│ ❯ 1. Yes                     │",
        "│   2. Yes, and don't ask again │",
        "│   3. No, and tell Claude     │",
        "╰──────────────────────────────╯",
        "",
        "",
    )

    @Test fun parsesPollOutput() {
        val out = "\u001eW\t0\t0\tclaude\tapi\n" + approval.joinToString("\n") + "\n" +
            "\u001eW\t1\t1\tnode\tweb\n✻ Thinking… (3s · esc to interrupt)\n> \n" +
            "\u001eW\t2\t0\tbash\tshell\n\$ ls\nREADME.md\n\n"
        val w = TmuxStatus.parse(out, emptyMap())
        assertEquals(listOf(0, 1, 2), w.map { it.index })
        assertEquals(listOf("api", "web", "shell"), w.map { it.name })
        assertEquals(listOf("claude", "node", "bash"), w.map { it.command })
        assertEquals(listOf(false, true, false), w.map { it.active })
        assertEquals(listOf(AgentState.WAITING, AgentState.WORKING, AgentState.IDLE), w.map { it.state })

        // Changed text since the last poll = something is running; same text (trailing blank
        // lines aside) = idle.
        val again = TmuxStatus.parse("\u001eW\t2\t0\tbash\tshell\n\$ ls\nREADME.md\n", w.associateBy { it.index })
        assertEquals(AgentState.IDLE, again[0].state)
        val changed = TmuxStatus.parse("\u001eW\t2\t0\tbash\tshell\n\$ make\nbuilding…\n", w.associateBy { it.index })
        assertEquals(AgentState.WORKING, changed[0].state)
    }

    @Test fun emptyOutputMeansNoWindows() {
        assertTrue(TmuxStatus.parse("", emptyMap()).isEmpty())
    }

    @Test fun promptNeedsQuestionAndTwoOptions() {
        assertTrue(TmuxStatus.findPrompt(approval))
        assertFalse(TmuxStatus.findPrompt(listOf("Do you want to proceed?", "1. Yes")))
        assertFalse(TmuxStatus.findPrompt(listOf("1. first", "2. second")))
    }

    @Test fun commandsAreSafeForShSingleQuotes() {
        for (cmd in listOf(TmuxStatus.pollCommand("ma'in"), TmuxStatus.selectCommand("main", 3))) {
            assertTrue(cmd.startsWith("sh -c '") && cmd.endsWith("'"))
            assertFalse(cmd.removePrefix("sh -c '").removeSuffix("'").contains('\''))
        }
        assertTrue(TmuxStatus.pollCommand("main").contains("list-windows -t \"=main\""))
        assertTrue(TmuxStatus.selectCommand("main", 3).contains("select-window -t \"=main:3\""))
    }

    /** Writes the poll script to a file so it can be tried against a real tmux. */
    @Test fun dumpScript() {
        java.io.File(System.getProperty("java.io.tmpdir"), "panoterm-tmux-poll.txt").writeText(TmuxStatus.pollCommand("main"))
    }
}
