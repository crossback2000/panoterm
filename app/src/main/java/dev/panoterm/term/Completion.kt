package dev.panoterm.term

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/** One autocomplete bubble. [insert] is what gets typed when it is tapped. */
data class Suggestion(val label: String, val insert: String, val hint: String = "")

/** What is being typed at the cursor. */
sealed interface CompletionContext {
    /** Claude Code prompt starting with "/" (token includes the "/"). */
    data class ClaudeSlash(val token: String) : CompletionContext
    /** Claude Code prompt, "@path" file mention (token is the part after "@"). */
    data class ClaudeFile(val token: String) : CompletionContext
    /** Shell, first word: a command name. */
    data class ShellCommand(val token: String) : CompletionContext
    /** Shell argument: a path (only directories after cd). */
    data class ShellPath(val token: String, val dirsOnly: Boolean) : CompletionContext
}

object Completion {
    // Claude Code's input box: "│ > text" or "> text" at the start of the line.
    private val claudeLine = Regex("^\\s*[│┃|]?\\s*>\\s?(.*)$")
    private val promptMarkers = listOf("$ ", "# ", "% ", "❯ ", "➜ ", "λ ", "> ")
    private val dirCommands = setOf("cd", "pushd", "rmdir", "z")
    // "user@host:~/proj$" style prompts → the working directory.
    private val promptCwd = Regex(":(~[^\\s$#%]*|/[^\\s$#%]*)\\s*[$#%]")

    fun parse(lineBeforeCursor: String): CompletionContext? {
        val line = lineBeforeCursor.replace(' ', ' ')
        claudeLine.matchEntire(line)?.let { m ->
            val rest = m.groupValues[1]
            if (rest.startsWith("/") && !rest.contains(' ')) return CompletionContext.ClaudeSlash(rest)
            val tok = rest.substringAfterLast(' ')
            if (tok.startsWith("@")) return CompletionContext.ClaudeFile(tok.substring(1))
            return null
        }
        // Shell: whatever follows the last prompt marker.
        val start = promptMarkers.maxOf { mk -> line.lastIndexOf(mk).let { if (it < 0) -1 else it + mk.length } }
        if (start < 0) return null
        val cmd = line.substring(start).trimStart()
        if (cmd.isEmpty()) return null
        val words = cmd.split(' ')
        val token = words.last()
        if (token.isEmpty()) return null // just typed a space
        if (words.size == 1) {
            return if (token.contains('/')) CompletionContext.ShellPath(token, false) else CompletionContext.ShellCommand(token)
        }
        return CompletionContext.ShellPath(token, words.first() in dirCommands)
    }

    /** Working directory shown in a classic "user@host:~/dir$" prompt, or "". */
    fun cwdFromPrompt(line: String): String = promptCwd.find(line)?.groupValues?.get(1) ?: ""

    /** POSIX single-quote, safe inside sh/bash/zsh/fish. */
    fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Server-side script: resolve the interactive shell's working directory (tmux pane path
     * first, then [fallbackCwd]), then list [dir] (ls -p marks directories with "/").
     * First output line is "CWD:<path>".
     */
    fun listingCommand(tmuxSession: String?, fallbackCwd: String, dir: String): String {
        val s = StringBuilder()
        s.append("c=\n")
        if (tmuxSession != null) {
            s.append("for t in tmux /opt/homebrew/bin/tmux /usr/local/bin/tmux \"\$HOME/.local/bin/tmux\"; do ")
            s.append("c=\$(\$t display-message -p -t ${shq(tmuxSession)} \"#{pane_current_path}\" 2>/dev/null) && [ -n \"\$c\" ] && break; done\n")
        }
        s.append("[ -z \"\$c\" ] && c=${shq(fallbackCwd)}\n")
        s.append("case \"\$c\" in \"~\"*) c=\"\$HOME\${c#\"~\"}\";; esac\n")
        s.append("cd \"\$c\" 2>/dev/null || cd\n")
        s.append("d=${shq(dir)}\n")
        s.append("case \"\$d\" in \"~\"*) d=\"\$HOME\${d#\"~\"}\";; esac\n")
        s.append("echo \"CWD:\$(pwd)\"\n")
        s.append("ls -1Ap -- \"\${d:-.}\" 2>/dev/null | head -n 500\n")
        return "sh -c " + shq(s.toString())
    }

    /** Command names on the server (login PATH so Homebrew etc. are included). */
    const val COMMANDS_CMD =
        "sh -c 'if command -v bash >/dev/null 2>&1; then bash -lc \"compgen -c\" </dev/null 2>/dev/null; " +
            "else ls /usr/local/bin /usr/bin /bin 2>/dev/null; fi | sort -u | head -n 8000'"

    /** The user's own Claude Code slash commands (~/.claude/commands, ./.claude/commands). */
    fun customClaudeCommand(tmuxSession: String?, fallbackCwd: String): String =
        listingCommand(tmuxSession, fallbackCwd, "~/.claude/commands").removeSuffix("'") +
            "ls -1 .claude/commands 2>/dev/null\n'"

    /** Filters an `ls -1Ap` listing for [token] ("src/ma" → entries of src/ starting with "ma"). */
    fun pathSuggestions(listing: List<String>, token: String, dirsOnly: Boolean): List<Suggestion> {
        val base = token.substringAfterLast('/')
        return listing.asSequence()
            .filter { it.isNotBlank() && it.startsWith(base) && it != base }
            .filter { base.startsWith(".") || !it.startsWith(".") }
            .filter { !dirsOnly || it.endsWith("/") }
            .sortedWith(compareBy<String>({ !it.endsWith("/") }, { it.lowercase() }))
            .take(40)
            .map { name ->
                val rest = name.removePrefix(base)
                Suggestion(label = name, insert = if (name.endsWith("/")) rest else "$rest ")
            }.toList()
    }
}

/** Built-in Claude Code slash commands (custom ones are read from the server). */
object ClaudeCommands {
    val builtin: List<Pair<String, String>> = listOf(
        "/add-dir" to "작업 폴더 추가",
        "/agents" to "서브에이전트 관리",
        "/bashes" to "백그라운드 작업",
        "/clear" to "대화 지우기",
        "/compact" to "대화 요약해서 줄이기",
        "/config" to "설정",
        "/context" to "컨텍스트 사용량",
        "/cost" to "비용",
        "/doctor" to "설치 점검",
        "/exit" to "종료",
        "/export" to "대화 내보내기",
        "/help" to "도움말",
        "/hooks" to "훅 설정",
        "/ide" to "IDE 연결",
        "/init" to "CLAUDE.md 만들기",
        "/login" to "로그인",
        "/logout" to "로그아웃",
        "/mcp" to "MCP 서버",
        "/memory" to "메모리 편집",
        "/model" to "모델 바꾸기",
        "/output-style" to "출력 스타일",
        "/permissions" to "권한",
        "/plugin" to "플러그인",
        "/pr-comments" to "PR 댓글 보기",
        "/release-notes" to "변경 사항",
        "/resume" to "이전 대화 이어하기",
        "/review" to "코드 리뷰",
        "/rewind" to "되돌리기",
        "/security-review" to "보안 리뷰",
        "/status" to "상태",
        "/statusline" to "상태 줄 설정",
        "/terminal-setup" to "터미널 설정",
        "/todos" to "할 일 목록",
        "/upgrade" to "업그레이드",
        "/usage" to "사용량",
        "/vim" to "vim 모드",
    )
}

/**
 * Watches what the user types and offers completions for the text at the cursor.
 * Server lookups run over a side SSH channel ([TermSession.execQuiet]) so nothing is
 * typed into the terminal.
 */
class Completer(
    private val session: TermSession,
    private val readLine: ((String) -> Unit) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions
    @Volatile private var generation = 0
    private val refresh = Runnable { lookup() }

    @Volatile private var commandCache: List<String>? = null
    @Volatile private var claudeCustom: List<String>? = null

    var enabled: () -> Boolean = { true }

    fun onUserInput(b: ByteArray) {
        if (!enabled()) { clear(); return }
        // Enter / Ctrl+C / Esc end the current input: hide until the user types again.
        if (b.any { it == '\r'.code.toByte() || it == 3.toByte() } || (b.size == 1 && b[0] == 0x1b.toByte())) clear()
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 280)
    }

    fun clear() {
        generation++
        _suggestions.value = emptyList()
    }

    fun dispose() {
        main.removeCallbacks(refresh)
        clear()
    }

    private fun lookup() {
        val gen = ++generation
        readLine { line ->
            if (gen != generation) return@readLine
            val ctx = Completion.parse(line)
            if (ctx == null) { _suggestions.value = emptyList(); return@readLine }
            val tmux = session.tmuxName()
            val fallbackCwd = Completion.cwdFromPrompt(line)
            when (ctx) {
                is CompletionContext.ClaudeSlash -> {
                    publish(gen, slash(ctx.token))
                    if (claudeCustom == null) background(gen) {
                        val out = session.execQuiet(Completion.customClaudeCommand(tmux, fallbackCwd)) ?: return@background null
                        claudeCustom = out.lines().filter { it.endsWith(".md") }.map { "/" + it.removeSuffix(".md") }.distinct()
                        slash(ctx.token)
                    }
                }
                is CompletionContext.ShellCommand -> background(gen) {
                    val cmds = commandCache ?: session.execQuiet(Completion.COMMANDS_CMD, 6000)
                        ?.lines()?.filter { it.isNotBlank() }?.also { commandCache = it } ?: return@background null
                    cmds.asSequence().filter { it.startsWith(ctx.token) && it != ctx.token }
                        .sortedBy { it.length }.take(30)
                        .map { Suggestion(it, it.removePrefix(ctx.token) + " ") }.toList()
                }
                is CompletionContext.ShellPath -> background(gen) { paths(ctx.token, ctx.dirsOnly, tmux, fallbackCwd) }
                is CompletionContext.ClaudeFile -> background(gen) { paths(ctx.token, false, tmux, fallbackCwd) }
            }
        }
    }

    private fun slash(token: String): List<Suggestion> {
        val all = ClaudeCommands.builtin + (claudeCustom ?: emptyList()).map { it to "내 명령" }
        return all.distinctBy { it.first }
            .filter { it.first.startsWith(token, ignoreCase = true) && it.first != token }
            .map { (cmd, hint) -> Suggestion(cmd, cmd.substring(token.length.coerceAtMost(cmd.length)), hint) }
    }

    private fun paths(token: String, dirsOnly: Boolean, tmux: String?, fallbackCwd: String): List<Suggestion>? {
        val dir = if (token.contains('/')) token.substringBeforeLast('/') + "/" else ""
        val out = session.execQuiet(Completion.listingCommand(tmux, fallbackCwd, dir)) ?: return null
        val listing = out.lines().filterNot { it.startsWith("CWD:") }
        return Completion.pathSuggestions(listing, token, dirsOnly)
    }

    private fun background(gen: Int, work: () -> List<Suggestion>?) {
        thread(name = "complete") {
            val r = runCatching(work).getOrNull() ?: return@thread
            publish(gen, r)
        }
    }

    private fun publish(gen: Int, list: List<Suggestion>) {
        main.post { if (gen == generation) _suggestions.value = list }
    }
}
