package dev.panoterm.term

/**
 * Scrollback search inside tmux. With tmux attached, xterm.js only holds the visible screen
 * (tmux draws on the alternate screen); the history lives in tmux. So the search runs tmux's own
 * copy-mode search on the pane, over the side channel: tmux scrolls the pane and highlights the
 * matches itself, and the attached client shows it like any other output.
 *
 * Steps keep the copy-mode cursor on the start of the current match, so both directions move
 * exactly one match (tmux's search-reverse would first stop on the match it is already on).
 */
object TmuxSearch {
    enum class Op { START, OLDER, NEWER, END }

    /**
     * Server-side command for [op] on the active pane of tmux session [name]. START prints
     * "N <matching lines>" and enters copy mode at the newest match when there is one.
     * Same quoting rules as [TermSession.tmuxCommand]: the script contains no single quote, and
     * the query travels as printf octal escapes, so any text is safe.
     */
    fun command(name: String, op: Op, query: String): String {
        val n = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val script = mutableListOf(
            TmuxStatus.FIND_TMUX,
            "[ -n \"\$T\" ] || { echo E; exit 0; }",
            "P=\"=$n:\"",
            "Q=\$(printf \"${octal(query)}\")",
            // search-*-text (plain text, not a regex) exists since tmux 3.2.
            "B=search-backward-text; F=search-forward-text",
            "case \"\$(\"\$T\" -V 2>/dev/null)\" in \"tmux 1.\"*|\"tmux 2.\"*|\"tmux 3.0\"*|\"tmux 3.1\"|\"tmux 3.1\"[a-z]) B=search-backward; F=search-forward;; esac",
        )
        script += when (op) {
            Op.START -> listOf(
                "N=\$(\"\$T\" capture-pane -p -J -S - -t \"\$P\" 2>/dev/null | grep -c ${if (smartCaseFold(query)) "-i " else ""}-F -e \"\$Q\")",
                "echo \"N \${N:-0}\"",
                "[ \"\${N:-0}\" -gt 0 ] || exit 0",
                "\"\$T\" send -X -t \"\$P\" cancel 2>/dev/null",
                "\"\$T\" copy-mode -t \"\$P\" && \"\$T\" send -X -t \"\$P\" \$B \"\$Q\"",
            )
            Op.OLDER -> listOf(
                "\"\$T\" copy-mode -t \"\$P\" && \"\$T\" send -X -t \"\$P\" \$B \"\$Q\"",
            )
            // Forward search leaves the cursor at the end of the match: search back to its start.
            Op.NEWER -> listOf(
                "\"\$T\" copy-mode -t \"\$P\" && \"\$T\" send -X -t \"\$P\" cursor-right && " +
                    "\"\$T\" send -X -t \"\$P\" \$F \"\$Q\" && \"\$T\" send -X -t \"\$P\" \$B \"\$Q\"",
            )
            Op.END -> listOf("\"\$T\" send -X -t \"\$P\" cancel 2>/dev/null")
        }
        return "sh -c '${script.joinToString("\n")}'"
    }

    /** Matching lines from START's output; null when tmux could not be asked. */
    fun parseCount(out: String?): Int? =
        out?.lineSequence()?.firstOrNull { it.startsWith("N ") }?.substring(2)?.trim()?.toIntOrNull()

    /** tmux (and grep -i here) ignore case when the query has no capitals. */
    fun smartCaseFold(query: String): Boolean = query == query.lowercase()

    /** Every UTF-8 byte as a printf octal escape (\ooo). */
    fun octal(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) append('\\').append(Integer.toOctalString(b.toInt() and 0xff).padStart(3, '0'))
    }
}
