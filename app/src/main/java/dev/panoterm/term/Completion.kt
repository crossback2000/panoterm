package dev.panoterm.term

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/** One autocomplete bubble. [insert] is what gets typed when it is tapped. */
data class Suggestion(val label: String, val insert: String, val hint: String = "")

/**
 * What the terminal shows around the cursor (TerminalWebView.inputContext).
 * [claude]: the line sits in Claude Code's input box. [menu]: commands Claude's own "/" menu
 * shows (command to description). Cursor rows in view pixels, -1 when off screen.
 */
data class InputContext(
    val line: String,
    val claude: Boolean = false,
    val menu: List<Pair<String, String>> = emptyList(),
    val cursorTop: Int = -1,
    val cursorBottom: Int = -1,
)

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
    // Newer Claude Code: "❯ text" between two ─── rules. "❯" alone is also a common shell
    // prompt (starship, pure), so it only counts when the screen shows Claude's box.
    private val claudeBoxLine = Regex("^\\s*[│┃|]?\\s*[>❯]\\s?(.*)$")
    private val promptMarkers = listOf("$ ", "# ", "% ", "❯ ", "➜ ", "λ ", "> ")
    private val dirCommands = setOf("cd", "pushd", "rmdir", "z")
    // "user@host:~/proj$" style prompts → the working directory.
    private val promptCwd = Regex(":(~[^\\s$#%]*|/[^\\s$#%]*)\\s*[$#%]")

    /** [inClaudeBox]: the screen shows Claude Code's input box around this line. */
    fun parse(lineBeforeCursor: String, inClaudeBox: Boolean = false): CompletionContext? {
        val line = lineBeforeCursor.replace(' ', ' ')
        (if (inClaudeBox) claudeBoxLine else claudeLine).matchEntire(line)?.let { m ->
            val rest = m.groupValues[1]
            if (rest.startsWith("/") && !rest.contains(' ')) return CompletionContext.ClaudeSlash(rest)
            val tok = rest.substringAfterLast(' ')
            if (tok.startsWith("@")) return CompletionContext.ClaudeFile(tok.substring(1))
            return null
        }
        // Inside Claude's box but not its prompt line (a wrapped or multi-line message).
        if (inClaudeBox) return null
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

    /**
     * The user's own Claude Code slash commands (~/.claude/commands, ./.claude/commands: "x.md")
     * and skills (~/.claude/skills, ./.claude/skills: "SKILL:x").
     */
    fun customClaudeCommand(tmuxSession: String?, fallbackCwd: String): String =
        listingCommand(tmuxSession, fallbackCwd, "~/.claude/commands").removeSuffix("'") +
            "ls -1 .claude/commands 2>/dev/null\n" +
            "for d in \"\$HOME/.claude/skills\" .claude/skills; do for f in \"\$d\"/*/SKILL.md; do " +
            "[ -f \"\$f\" ] && echo \"SKILL:\$(basename \"\$(dirname \"\$f\")\")\"; done; done\n'"

    /** [token]'s characters appear in [cmd] in order (how Claude Code's own menu matches). */
    fun fuzzy(cmd: String, token: String): Boolean {
        var i = 0
        for (c in cmd) if (i < token.length && c.equals(token[i], ignoreCase = true)) i++
        return i == token.length
    }

    /**
     * Slash command bubbles: what Claude Code's own menu shows ([menu], its ranking) first,
     * then built-in and custom commands starting with [token].
     */
    fun slashSuggestions(token: String, menu: List<Pair<String, String>>, known: List<Pair<String, String>>): List<Suggestion> {
        val hints = known.toMap()
        val fromMenu = menu.filter { fuzzy(it.first, token) }
        val rest = known.filter { it.first.startsWith(token, ignoreCase = true) }
        return (fromMenu + rest).distinctBy { it.first }
            .filter { it.first != token }
            .take(40)
            .map { (cmd, desc) ->
                val hint = hints[cmd] ?: desc.let { if (it.length > 28) it.take(27) + "…" else it }
                // Fuzzy matches don't extend what was typed: erase it and type the command.
                val insert = if (cmd.startsWith(token)) cmd.substring(token.length) else "\u007f".repeat(token.length) + cmd
                Suggestion(cmd, insert, hint)
            }
    }

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

/**
 * Built-in Claude Code slash commands, for before Claude's own menu shows up on screen
 * (that menu is read too and always wins, so this list may lag behind new versions).
 * Checked against Claude Code 2.1.
 */
object ClaudeCommands {
    val builtin: List<Pair<String, String>> = listOf(
        "/add-dir" to "작업 폴더 추가",
        "/agents" to "서브에이전트",
        "/btw" to "곁가지 질문",
        "/clear" to "대화 지우기",
        "/color" to "프롬프트 색",
        "/compact" to "대화 요약해서 줄이기",
        "/config" to "설정",
        "/context" to "컨텍스트 사용량",
        "/copy" to "마지막 답 복사",
        "/doctor" to "설치 점검",
        "/effort" to "생각 강도",
        "/exit" to "종료",
        "/export" to "대화 내보내기",
        "/fast" to "빠른 모드",
        "/feedback" to "피드백",
        "/help" to "도움말",
        "/hooks" to "훅 설정",
        "/ide" to "IDE 연결",
        "/init" to "CLAUDE.md 만들기",
        "/keybindings" to "단축키 파일",
        "/login" to "로그인",
        "/logout" to "로그아웃",
        "/mcp" to "MCP 서버",
        "/memory" to "메모리 편집",
        "/model" to "모델 바꾸기",
        "/permissions" to "권한",
        "/plan" to "계획 모드",
        "/plugin" to "플러그인",
        "/release-notes" to "변경 사항",
        "/rename" to "대화 이름 바꾸기",
        "/resume" to "이전 대화 이어하기",
        "/rewind" to "되돌리기",
        "/skills" to "스킬 목록",
        "/status" to "상태",
        "/statusline" to "상태 줄 설정",
        "/tasks" to "백그라운드 작업",
        "/terminal-setup" to "터미널 설정",
        "/theme" to "테마",
        "/todos" to "할 일 목록",
        "/upgrade" to "업그레이드",
        "/usage" to "사용량",
    )
}

/**
 * Watches what the user types and offers completions for the text at the cursor.
 * Server lookups run over a side SSH channel ([TermSession.execQuiet]) so nothing is
 * typed into the terminal.
 */
class Completer(
    private val session: TermSession,
    private val readContext: ((InputContext) -> Unit) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions
    /** Cursor row (top to bottom, view pixels) the bubbles belong to; null = unknown. */
    private val _anchor = MutableStateFlow<IntRange?>(null)
    val anchor: StateFlow<IntRange?> = _anchor
    @Volatile private var generation = 0
    private val refresh = Runnable { lookup() }
    // Echo and Claude's menu come back over the network: look once more a bit later.
    private val refreshLate = Runnable { lookup() }

    @Volatile private var commandCache: List<String>? = null
    @Volatile private var claudeCustom: List<Pair<String, String>>? = null
    /** Directory listings, briefly: typing a path asks for the same folder again and again. */
    private val listings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

    var enabled: () -> Boolean = { true }

    fun onUserInput(b: ByteArray) {
        if (!enabled()) { clear(); return }
        // Enter / Ctrl+C / Esc end the current input: hide until the user types again.
        if (b.any { it == '\r'.code.toByte() || it == 3.toByte() } || (b.size == 1 && b[0] == 0x1b.toByte())) {
            clear()
            listings.clear() // the command may have changed folders (cd, mkdir, rm...)
        }
        main.removeCallbacks(refresh)
        main.removeCallbacks(refreshLate)
        main.postDelayed(refresh, 90)
        main.postDelayed(refreshLate, 380)
    }

    fun clear() {
        generation++
        _suggestions.value = emptyList()
    }

    fun dispose() {
        main.removeCallbacks(refresh)
        main.removeCallbacks(refreshLate)
        clear()
    }

    private fun lookup() {
        val gen = ++generation
        readContext { ic ->
            if (gen != generation) return@readContext
            val anchor = if (ic.cursorTop >= 0) ic.cursorTop..ic.cursorBottom else null
            val ctx = Completion.parse(ic.line, ic.claude)
            if (ctx == null) { _suggestions.value = emptyList(); return@readContext }
            val tmux = session.tmuxName()
            val fallbackCwd = Completion.cwdFromPrompt(ic.line)
            when (ctx) {
                is CompletionContext.ClaudeSlash -> {
                    publish(gen, anchor, slash(ctx.token, ic.menu))
                    if (claudeCustom == null) background(gen, anchor) {
                        val out = session.execQuiet(Completion.customClaudeCommand(tmux, fallbackCwd)) ?: return@background null
                        claudeCustom = out.lines().mapNotNull { l ->
                            when {
                                l.endsWith(".md") -> "/" + l.removeSuffix(".md") to "내 명령"
                                l.startsWith("SKILL:") -> "/" + l.removePrefix("SKILL:") to "스킬"
                                else -> null
                            }
                        }.distinctBy { it.first }
                        slash(ctx.token, ic.menu)
                    }
                }
                is CompletionContext.ShellCommand -> background(gen, anchor) {
                    val cmds = commandCache ?: session.execQuiet(Completion.COMMANDS_CMD, 6000)
                        ?.lines()?.filter { it.isNotBlank() }?.also { commandCache = it } ?: return@background null
                    cmds.asSequence().filter { it.startsWith(ctx.token) && it != ctx.token }
                        .sortedBy { it.length }.take(30)
                        .map { Suggestion(it, it.removePrefix(ctx.token) + " ") }.toList()
                }
                is CompletionContext.ShellPath -> background(gen, anchor) { paths(ctx.token, ctx.dirsOnly, tmux, fallbackCwd) }
                is CompletionContext.ClaudeFile -> background(gen, anchor) { paths(ctx.token, false, tmux, fallbackCwd) }
            }
        }
    }

    private fun slash(token: String, menu: List<Pair<String, String>>): List<Suggestion> =
        Completion.slashSuggestions(token, menu, ClaudeCommands.builtin + (claudeCustom ?: emptyList()))

    private fun paths(token: String, dirsOnly: Boolean, tmux: String?, fallbackCwd: String): List<Suggestion>? {
        val dir = if (token.contains('/')) token.substringBeforeLast('/') + "/" else ""
        val key = "$tmux|$fallbackCwd|$dir"
        val now = System.currentTimeMillis()
        val listing = listings[key]?.takeIf { now - it.first < 8000 }?.second ?: run {
            val out = session.execQuiet(Completion.listingCommand(tmux, fallbackCwd, dir)) ?: return null
            out.lines().filterNot { it.startsWith("CWD:") }.also { listings[key] = now to it }
        }
        return Completion.pathSuggestions(listing, token, dirsOnly)
    }

    private fun background(gen: Int, anchor: IntRange?, work: () -> List<Suggestion>?) {
        thread(name = "complete") {
            val r = runCatching(work).getOrNull() ?: return@thread
            publish(gen, anchor, r)
        }
    }

    private fun publish(gen: Int, anchor: IntRange?, list: List<Suggestion>) {
        main.post {
            if (gen != generation) return@post
            _anchor.value = anchor
            _suggestions.value = list
        }
    }
}
