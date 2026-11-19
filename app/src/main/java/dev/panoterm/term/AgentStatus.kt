package dev.panoterm.term

/**
 * What an agent (Claude Code, a build...) in a session or tmux window is doing, for the
 * status tabs. Ordered by how urgently the user should look: WAITING beats WORKING beats IDLE.
 */
enum class AgentState { OFFLINE, IDLE, WORKING, WAITING }

fun maxState(a: AgentState, b: AgentState): AgentState = if (a.ordinal >= b.ordinal) a else b

/**
 * Activity of one session from its raw output stream (works without tmux and while no
 * screen shows the session):
 *  - WAITING: an approval prompt is on screen, or the program rang the bell / sent a
 *    notification (Claude Code's Notification hook) and the user hasn't answered or looked yet.
 *  - WORKING: steady output (a spinner, a build log). Echo of the user's own typing and
 *    occasional redraws (a tmux status clock) don't count.
 */
class ActivityTracker(private val now: () -> Long = System::currentTimeMillis) {
    private val chunks = LongArray(64)
    private var head = 0
    private var count = 0
    private var lastInput = 0L
    @Volatile var promptOnScreen = false
    @Volatile var attention = false
        private set

    @Synchronized
    fun onOutput() {
        val t = now()
        if (t - lastInput < ECHO_MS) return
        chunks[head] = t
        head = (head + 1) % chunks.size
        if (count < chunks.size) count++
    }

    @Synchronized
    fun onInput() {
        lastInput = now()
        attention = false
    }

    fun onAttention() { attention = true }

    /** The user is looking at this session: a bell or notification has been seen. */
    fun seen() { attention = false }

    @Synchronized
    fun state(): AgentState {
        if (promptOnScreen || attention) return AgentState.WAITING
        val since = now() - WINDOW_MS
        var recent = 0
        for (i in 0 until count) if (chunks[i] >= since) recent++
        return if (recent >= WORKING_CHUNKS) AgentState.WORKING else AgentState.IDLE
    }

    companion object {
        const val ECHO_MS = 800L
        const val WINDOW_MS = 3000L
        /** A spinner redraws many times a second; a tmux status clock at most once. */
        const val WORKING_CHUNKS = 6
    }
}

/** One tmux window of the session, as last polled. */
data class TmuxWindow(
    val index: Int,
    val name: String,
    val command: String,
    val active: Boolean,
    val state: AgentState,
    /** Hash of the visible pane text: a change between polls = something is running. */
    val hash: Int,
)

object TmuxStatus {
    private const val RS = '\u001e'

    /**
     * Server-side script listing the windows of tmux session [name] with each window's visible
     * pane text. Output per window: a header "<RS>W\t<index>\t<active>\t<command>\t<name>",
     * then the pane lines. Prints nothing when tmux or the session is missing.
     * Same quoting rules as [TermSession.tmuxCommand] (no single quotes inside).
     */
    fun pollCommand(name: String): String {
        val n = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val script = listOf(
            "T=",
            "for t in tmux /opt/homebrew/bin/tmux /usr/local/bin/tmux /home/linuxbrew/.linuxbrew/bin/tmux " +
                "\"\$HOME/.local/bin/tmux\" \"\$HOME/.nix-profile/bin/tmux\" /run/current-system/sw/bin/tmux; do " +
                "if command -v \"\$t\" >/dev/null 2>&1; then T=\$t; break; fi; done",
            "[ -n \"\$T\" ] || exit 0",
            "\"\$T\" list-windows -t \"=$n\" -F \"#{window_index}\t#{window_active}\t#{pane_current_command}\t#{window_name}\" 2>/dev/null |",
            "while IFS=\"\t\" read -r i a c w; do",
            "printf \"\\036W\\t%s\\t%s\\t%s\\t%s\\n\" \"\$i\" \"\$a\" \"\$c\" \"\$w\"",
            "\"\$T\" capture-pane -p -J -t \"=$n:\$i\" 2>/dev/null",
            "done",
        )
        return "sh -c '${script.joinToString("\n")}'"
    }

    /** Command that makes the attached client show window [index] of session [name]. */
    fun selectCommand(name: String, index: Int): String {
        val n = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "sh -c 'for t in tmux /opt/homebrew/bin/tmux /usr/local/bin/tmux /home/linuxbrew/.linuxbrew/bin/tmux " +
            "\"\$HOME/.local/bin/tmux\" \"\$HOME/.nix-profile/bin/tmux\" /run/current-system/sw/bin/tmux; do " +
            "if command -v \"\$t\" >/dev/null 2>&1; then exec \"\$t\" select-window -t \"=$n:$index\"; fi; done'"
    }

    /**
     * Parses [pollCommand]'s output. [previous] (by window index) gives the last hashes so a
     * pane whose text changed since the last poll counts as WORKING.
     */
    fun parse(out: String, previous: Map<Int, TmuxWindow>): List<TmuxWindow> {
        val result = mutableListOf<TmuxWindow>()
        val blocks = out.split(RS).filter { it.startsWith("W\t") }
        for (b in blocks) {
            val nl = b.indexOf('\n').let { if (it < 0) b.length else it }
            val head = b.substring(2, nl).split('\t', limit = 4)
            val index = head.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val lines = if (nl < b.length) b.substring(nl + 1).split('\n') else emptyList()
            val text = lines.dropLastWhile { it.isBlank() }
            val hash = text.joinToString("\n").hashCode()
            val prev = previous[index]?.hash
            result += TmuxWindow(
                index = index,
                active = head.getOrNull(1)?.trim() == "1",
                command = head.getOrNull(2)?.trim().orEmpty(),
                name = head.getOrNull(3)?.trim().orEmpty(),
                state = classify(text, changed = prev != null && prev != hash),
                hash = hash,
            )
        }
        return result
    }

    fun classify(lines: List<String>, changed: Boolean): AgentState = when {
        findPrompt(lines) -> AgentState.WAITING
        // Claude Code's status line while it works: "✻ Thinking… (12s · esc to interrupt)".
        lines.takeLast(12).any { it.contains("esc to interrupt", ignoreCase = true) } -> AgentState.WORKING
        changed -> AgentState.WORKING
        else -> AgentState.IDLE
    }

    // Same rules as the on-screen approval detection in term.js.
    private val QUESTION = Regex("(Do you want|Would you like|Allow |Proceed\\?|진행하시겠|허용하시겠)")
    private val OPTION = Regex("^\\s*[│┃|]?\\s*(?:❯|›|>|▶)?\\s*(\\d)\\.\\s+(.+?)\\s*[│┃|]?\\s*$")

    /** A question line followed by at least two numbered options. */
    fun findPrompt(lines: List<String>): Boolean {
        val qi = lines.indexOfLast { QUESTION.containsMatchIn(it) }
        if (qi < 0) return false
        return lines.drop(qi + 1).count { OPTION.matches(it) } >= 2
    }
}
