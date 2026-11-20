package dev.panoterm.term

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import core.ConnectOptions
import core.Core
import core.HostKeyVerifier
import core.Session
import core.SessionListener
import dev.panoterm.App
import dev.panoterm.Notifier
import dev.panoterm.data.Host
import dev.panoterm.data.KnownHosts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Host-key confirmation shown by the UI while the SSH handshake waits. */
data class HostKeyPrompt(
    val hostPort: String,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean,
    val result: CompletableFuture<Boolean> = CompletableFuture(),
)

object Prompts {
    val hostKey = MutableStateFlow<HostKeyPrompt?>(null)
}

/** Fixed-size ring buffer: output that arrives before the session's terminal exists. */
class ByteRing(private val cap: Int) {
    private val data = ByteArray(cap)
    private var start = 0
    private var size = 0

    fun write(src0: ByteArray) {
        var src = src0
        if (src.size >= cap) {
            src = src.copyOfRange(src.size - cap, src.size)
            start = 0; size = 0
        }
        val overflow = size + src.size - cap
        if (overflow > 0) {
            start = (start + overflow) % cap
            size -= overflow
        }
        val pos = (start + size) % cap
        val first = minOf(src.size, cap - pos)
        System.arraycopy(src, 0, data, pos, first)
        if (src.size > first) System.arraycopy(src, first, data, 0, src.size - first)
        size += src.size
    }

    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        val first = minOf(size, cap - start)
        System.arraycopy(data, start, out, 0, first)
        if (size > first) System.arraycopy(data, 0, out, first, size - first)
        return out
    }
}

data class PortForward(val remoteHost: String, val remotePort: Int, val localPort: Int)

class TermSession(
    host0: Host,
    private val app: App,
    /** Command for the first connect only (e.g. the tmux installer); reconnects use the normal one. */
    private var oneShotCommand: String? = null,
    /** Shown after the host title in tabs, e.g. "tmux 설치". */
    val label: String? = null,
) {
    enum class State { IDLE, CONNECTING, CONNECTED, CLOSED }

    val id: String = UUID.randomUUID().toString()

    @Volatile var host: Host = host0
        private set

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _forwards = MutableStateFlow<List<PortForward>>(emptyList())
    val forwards: StateFlow<List<PortForward>> = _forwards
    /** Seconds until the next automatic reconnect, or null. */
    private val _reconnectIn = MutableStateFlow<Int?>(null)
    val reconnectIn: StateFlow<Int?> = _reconnectIn
    /** Host is set to use tmux but the server had no tmux: work will not survive a disconnect. */
    private val _noTmux = MutableStateFlow(false)
    val noTmux: StateFlow<Boolean> = _noTmux

    /** Output-based activity of this session (see [ActivityTracker]). */
    val activity = ActivityTracker()
    private val _agent = MutableStateFlow(AgentState.OFFLINE)
    /** What the agent(s) in this session are doing, for the status tabs (updated every second). */
    val agent: StateFlow<AgentState> = _agent
    private val _windows = MutableStateFlow<List<TmuxWindow>>(emptyList())
    /** The tmux windows of this session with their state (empty without tmux or before the first poll). */
    val windows: StateFlow<List<TmuxWindow>> = _windows
    private val poller = Executors.newSingleThreadExecutor()
    @Volatile private var polling = false
    private var lastPoll = 0L
    private var pollFailures = 0

    @Volatile var appCursor = false
    @Volatile var bracketedPaste = false

    @Volatile private var go: Session? = null
    @Volatile private var generation = 0
    private val lock = Any()
    /** Output until the terminal is attached (normally just the first "connecting" lines). */
    private var early: ByteRing? = ByteRing(256 shl 10)
    private var sink: ((ByteArray) -> Unit)? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cols = 80
    private var rows = 24

    /** True once the user has connected successfully and hasn't disconnected: drives auto-reconnect. */
    @Volatile private var wantConnected = false
    private var attempt = 0
    private var reconnectTask: Runnable? = null
    private var lastNotify = 0L

    private val scanner = OutputScanner(
        onBell = {
            activity.onAttention()
            if (app.settings.current.notifyOnBell) notifyUser(host.title, "입력을 기다리고 있습니다 (터미널 벨)")
        },
        onNotify = { title, body ->
            activity.onAttention()
            notifyUser(title.ifBlank { host.title }, body.ifBlank { "알림" })
        },
        onClipboard = { text ->
            if (app.settings.current.remoteClipboard) main.post {
                app.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("remote", text))
            }
        },
        onNoTmux = { _noTmux.value = true },
    )

    fun updateHost(h: Host) {
        if (h.id == host.id) host = h
    }

    // ---- output ----

    private fun emit(b: ByteArray) = synchronized(lock) {
        val s = sink
        if (s != null) s(b) else early?.write(b)
    }

    private fun note(text: String) = emit("\r\n\u001b[33m[PanoTerm] $text\u001b[0m\r\n".toByteArray())

    /**
     * Connects the session's terminal (once, for the session's lifetime): hands over output that
     * arrived before it, then streams all new output to [s].
     */
    fun attach(s: (ByteArray) -> Unit) = synchronized(lock) {
        early?.snapshot()?.takeIf { it.isNotEmpty() }?.let(s)
        early = null
        sink = s
    }

    /** The terminal is gone (session closed): drop further output. */
    fun detach() = synchronized(lock) { sink = null }

    private fun notifyUser(title: String, body: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 4000) return
        // Only when the user is not already looking at this session.
        if (app.isForeground && app.visibleSessionId == id) return
        lastNotify = now
        Notifier.agent(app, id, title, body)
    }

    // ---- input ----

    fun send(b: ByteArray) {
        if (b.isEmpty()) return
        activity.onInput()
        writer.execute { runCatching { go?.write(b) } }
    }

    fun send(s: String) = send(s.toByteArray())

    fun paste(text: String) {
        val t = sanitizePaste(text)
        if (t.isEmpty()) return
        send(if (bracketedPaste) "\u001b[200~$t\u001b[201~" else t)
    }

    /** Called whenever the terminal view reports its size. The first call starts the connection. */
    fun onTerminalSize(c: Int, r: Int) {
        if (c <= 0 || r <= 0) return
        val changed = c != cols || r != rows
        cols = c; rows = r
        when (_state.value) {
            State.IDLE -> connect()
            State.CONNECTED -> if (changed) writer.execute { runCatching { go?.resize(c.toLong(), r.toLong()) } }
            else -> {}
        }
    }

    // ---- connection ----

    fun connect() {
        if (_state.value == State.CONNECTING || _state.value == State.CONNECTED) return
        cancelReconnect()
        _state.value = State.CONNECTING
        _message.value = null
        val gen = ++generation
        val h = host
        note("${h.user}@${h.address}:${h.port} 연결 중...")
        thread(name = "ssh-connect") {
            var pem: ByteArray? = null
            try {
                val keyId = h.keyId ?: throw IllegalStateException("이 호스트에 SSH 키가 지정되지 않았습니다")
                val node = if (h.useTailnet) {
                    waitForTailscale()
                    app.tailscale.nodeOrNull() ?: throw IllegalStateException("Tailscale이 시작되지 않았습니다")
                } else null
                val key = app.keys.get(keyId) ?: throw IllegalStateException("SSH 키를 찾을 수 없습니다 (삭제되었나요?)")
                if (!key.hardware) pem = app.keys.privateKey(keyId)
                val opts = ConnectOptions().apply {
                    setHost(h.address.trim())
                    setPort(h.port.toLong())
                    setUser(h.user.trim())
                    if (pem != null) setPrivateKey(pem)
                    setUseTailnet(h.useTailnet)
                    setCols(cols.toLong())
                    setRows(rows.toLong())
                    setCommand(startupCommand(h))
                    setEnv("MOBILE_TERM=1\nLANG=C.UTF-8")
                }
                val s = if (key.hardware) {
                    Core.connectHardware(node, opts, app.keys.spki(key), app.keys.hardwareSigner(key), verifier, listener(gen))
                } else {
                    Core.connect(node, opts, verifier, listener(gen))
                }
                if (gen != generation) { s.close(); return@thread }
                go = s
                wantConnected = true
                attempt = 0
                _forwards.value = emptyList()
                _state.value = State.CONNECTED
                writer.execute { runCatching { s.resize(cols.toLong(), rows.toLong()) } }
                main.post { startTicker() }
            } catch (e: Exception) {
                if (gen == generation) {
                    val msg = e.message ?: e.toString()
                    _message.value = msg
                    _state.value = State.CLOSED
                    note("연결 실패: $msg")
                    maybeScheduleReconnect()
                }
            } finally {
                pem?.fill(0)
            }
        }
    }

    /** User-initiated disconnect: no auto-reconnect. */
    fun disconnect() {
        wantConnected = false
        cancelReconnect()
        generation++
        go?.close()
        go = null
        _forwards.value = emptyList()
        _state.value = State.CLOSED
        main.post { tick() }
    }

    /** Network came back: retry right away instead of waiting for the backoff. */
    fun onNetworkAvailable() {
        if (_state.value == State.CLOSED && wantConnected && host.autoReconnect) main.post { connect() }
    }

    private fun maybeScheduleReconnect() {
        if (!wantConnected || !host.autoReconnect) return
        if (attempt >= 30) { note("자동 재연결 중단 (30회 실패)"); return }
        val delaySec = minOf(30, 1 shl minOf(attempt, 5))
        attempt++
        main.post {
            cancelReconnect()
            var left = delaySec
            _reconnectIn.value = left
            val tick = object : Runnable {
                override fun run() {
                    left--
                    if (left <= 0) { _reconnectIn.value = null; reconnectTask = null; connect() }
                    else { _reconnectIn.value = left; main.postDelayed(this, 1000) }
                }
            }
            reconnectTask = tick
            main.postDelayed(tick, 1000)
        }
    }

    private fun cancelReconnect() {
        reconnectTask?.let { main.removeCallbacks(it) }
        reconnectTask = null
        _reconnectIn.value = null
    }

    private fun waitForTailscale() {
        if (app.tailscale.isRunning) return
        note("Tailscale 연결 대기 중...")
        val deadline = System.currentTimeMillis() + 30_000
        while (!app.tailscale.isRunning) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("Tailscale 상태: ${app.tailscale.state.value} (로그인이 필요하면 호스트 목록에서 로그인하세요)")
            }
            Thread.sleep(300)
        }
    }

    private fun startupCommand(h: Host): String {
        oneShotCommand?.let { oneShotCommand = null; return it }
        if (!h.tmux) return ""
        val name = h.tmuxSession.ifBlank { "main" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return tmuxCommand(name)
    }

    companion object {
        /**
         * Pasted text with line endings as CR and every control character except Tab and CR
         * removed. Without this a clipboard holding "ESC[201~" (e.g. set by a server through
         * OSC 52) would end bracketed paste early and run whatever follows as typed commands.
         */
        fun sanitizePaste(text: String): String {
            val t = text.replace("\r\n", "\r").replace('\n', '\r')
            return t.filter { c -> c == '\t' || c == '\r' || !(c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f') }
        }

        /** Start of [writeFile]'s error when the file changed on the server (core.ConflictPrefix). */
        const val CONFLICT = "conflict:"

        /**
         * Shell command that attaches to (or creates) tmux session [name].
         *
         * sshd runs exec commands through the user's shell with a minimal, non-login PATH, so
         * a tmux from Homebrew / linuxbrew / ~/.local / nix is often invisible. We therefore
         * look it up via the login shell's PATH and a list of common locations.
         * Everything runs inside `sh -c '...'` so it works whatever the login shell is (fish, csh...).
         * The script must not contain single quotes.
         * If tmux is missing it emits OSC 777;panoterm;notmux so the app can warn the user.
         */
        fun tmuxCommand(name: String): String = "exec sh -c '${tmuxScript(name).joinToString("\n")}'"

        /**
         * Installs tmux with whatever package manager the server has, then attaches to tmux.
         * Runs in its own tab; sudo asks for the password in that terminal (the app never sees it).
         * Same quoting rules as [tmuxCommand].
         */
        fun installTmuxCommand(name: String): String {
            val install = listOf(
                "echo \"[PanoTerm] tmux 설치를 시작합니다. 비밀번호를 물으면 서버(sudo) 비밀번호를 입력하세요.\"",
                "S=\"\"; if [ \"\$(id -u)\" != \"0\" ] && command -v sudo >/dev/null 2>&1; then S=sudo; fi",
                "if [ \"\$(uname -s)\" = \"Darwin\" ]; then " +
                    "B=\$(command -v brew 2>/dev/null); for p in /opt/homebrew/bin/brew /usr/local/bin/brew; do [ -z \"\$B\" ] && [ -x \"\$p\" ] && B=\$p; done; " +
                    "if [ -n \"\$B\" ]; then \"\$B\" install tmux; else echo \"Homebrew가 없어요. https://brew.sh 에서 먼저 설치하세요.\"; fi; " +
                    "elif command -v apt-get >/dev/null 2>&1; then \$S apt-get update && \$S apt-get install -y tmux; " +
                    "elif command -v dnf >/dev/null 2>&1; then \$S dnf install -y tmux; " +
                    "elif command -v yum >/dev/null 2>&1; then \$S yum install -y tmux; " +
                    "elif command -v pacman >/dev/null 2>&1; then \$S pacman -S --noconfirm tmux; " +
                    "elif command -v zypper >/dev/null 2>&1; then \$S zypper --non-interactive install tmux; " +
                    "elif command -v apk >/dev/null 2>&1; then \$S apk add tmux; " +
                    "elif command -v pkg >/dev/null 2>&1; then \$S pkg install -y tmux; " +
                    "else echo \"이 서버의 패키지 관리자를 몰라요. tmux를 직접 설치해 주세요.\"; fi",
                "echo \"[PanoTerm] 설치 단계 끝. tmux로 들어갑니다...\"",
            )
            return "exec sh -c '${(install + tmuxScript(name)).joinToString("\n")}'"
        }

        private fun tmuxScript(name: String): List<String> =
            listOf(
                "export MOBILE_TERM=1",
                "T=\$(command -v tmux 2>/dev/null)",
                "if [ -z \"\$T\" ]; then T=\$(\"\${SHELL:-/bin/sh}\" -lc \"command -v tmux\" </dev/null 2>/dev/null | tail -n 1); fi",
                "for p in /opt/homebrew/bin/tmux /usr/local/bin/tmux /home/linuxbrew/.linuxbrew/bin/tmux " +
                    "\"\$HOME/.local/bin/tmux\" \"\$HOME/.nix-profile/bin/tmux\" /run/current-system/sw/bin/tmux /usr/bin/tmux /bin/tmux; do " +
                    "if [ -z \"\$T\" ] || [ ! -x \"\$T\" ]; then [ -x \"\$p\" ] && T=\$p; fi; done",
                "if [ -n \"\$T\" ] && [ -x \"\$T\" ]; then exec \"\$T\" -u new -A -s $name; fi",
                "printf \"\\033]777;panoterm;notmux\\007\"",
                "echo \"[PanoTerm] tmux를 찾지 못해 일반 셸로 접속했습니다. 연결이 끊기면 실행 중인 작업도 끝나요.\"",
                "exec \"\${SHELL:-/bin/sh}\" -l",
            )
    }

    private fun listener(gen: Int) = object : SessionListener {
        override fun onData(data: ByteArray) {
            if (gen != generation) return
            activity.onOutput()
            scanner.feed(data)
            emit(data)
        }

        override fun onClosed(reason: String) {
            if (gen != generation) return
            go = null
            _forwards.value = emptyList()
            _message.value = reason
            _state.value = State.CLOSED
            main.post { tick() }
            note("연결 종료: $reason")
            maybeScheduleReconnect()
        }
    }

    private val verifier = object : HostKeyVerifier {
        override fun verify(hostPort: String, keyType: String, fingerprint: String, keyBase64: String): Boolean {
            val st = app.knownHosts.check(hostPort, keyType, keyBase64)
            if (st == KnownHosts.Result.MATCH) return true
            val p = HostKeyPrompt(hostPort, keyType, fingerprint, changed = st == KnownHosts.Result.CHANGED)
            Prompts.hostKey.value = p
            val ok = try {
                p.result.get(140, TimeUnit.SECONDS)
            } catch (e: Exception) {
                false
            } finally {
                Prompts.hostKey.compareAndSet(p, null)
            }
            if (ok) app.knownHosts.save(hostPort, keyType, keyBase64)
            return ok
        }
    }

    // ---- extras ----

    /** tmux session name if this connection runs inside tmux, else null. */
    fun tmuxName(): String? =
        if (host.tmux && !_noTmux.value && label == null) host.tmuxSession.ifBlank { "main" }.replace(Regex("[^A-Za-z0-9_.-]"), "_") else null

    /**
     * Runs [command] on the server over a separate channel (nothing appears in the terminal).
     * Blocking; call off the main thread. Returns null when not connected or on error.
     */
    fun execQuiet(command: String, timeoutMs: Int = 3000): String? {
        val s = go ?: return null
        return runCatching { s.exec(command, timeoutMs.toLong()) }.getOrNull()
    }

    /** Uploads [data] via SFTP to ~/.cache/panoterm/ and returns the remote path through [done]. */
    fun upload(name: String, data: ByteArray, done: (Result<String>) -> Unit) {
        val s = go ?: return done(Result.failure(IllegalStateException("연결되어 있지 않습니다")))
        thread(name = "sftp-upload") {
            val r = runCatching { s.uploadFile(name, data) }
            main.post { done(r) }
        }
    }

    fun forward(remoteHost: String, remotePort: Int, done: (Result<PortForward>) -> Unit) {
        val s = go ?: return done(Result.failure(IllegalStateException("연결되어 있지 않습니다")))
        thread(name = "forward") {
            val r = runCatching {
                val local = s.forwardLocal(remoteHost, remotePort.toLong()).toInt()
                PortForward(remoteHost, remotePort, local).also { _forwards.value = _forwards.value + it }
            }
            main.post { done(r) }
        }
    }

    // ---- agent status (status tabs) ----

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (_state.value == State.CONNECTED) main.postDelayed(this, 1000)
        }
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    /** Main thread. Recomputes [agent] and starts a tmux poll when one is due. */
    private fun tick() {
        if (_state.value != State.CONNECTED) {
            _agent.value = AgentState.OFFLINE
            _windows.value = emptyList()
            return
        }
        val visible = app.isForeground && app.visibleSessionId == id
        if (visible) activity.seen()
        val windowsState = _windows.value.fold(AgentState.IDLE) { acc, w -> maxState(acc, w.state) }
        _agent.value = maxState(activity.state(), windowsState)

        // Nobody sees the tabs while the app is in the background: no polling then.
        val name = tmuxName()
        val interval = if (visible) 2000L else 5000L
        val now = System.currentTimeMillis()
        if (name == null || !app.isForeground || !app.settings.current.agentTabs || polling || now - lastPoll < interval) return
        lastPoll = now
        polling = true
        poller.execute {
            try { pollTmux(name) } finally { polling = false }
        }
    }

    /** Poller thread. */
    private fun pollTmux(name: String) {
        val out = execQuiet(TmuxStatus.pollCommand(name), 4000)
        if (out == null) {
            // Keep the last picture through a hiccup, drop it when the server stops answering.
            if (++pollFailures >= 3) _windows.value = emptyList()
            return
        }
        pollFailures = 0
        _windows.value = TmuxStatus.parse(out, _windows.value.associateBy { it.index })
    }

    /** Shows tmux window [index] in this session's terminal (the attached tmux client switches). */
    fun selectTmuxWindow(index: Int) {
        val name = tmuxName() ?: return
        // Show the switch right away; the next poll confirms it.
        _windows.value = _windows.value.map { it.copy(active = it.index == index) }
        poller.execute {
            execQuiet(TmuxStatus.selectCommand(name, index))
            pollTmux(name)
        }
    }

    // ---- files (SFTP browser) ----

    /** Blocking; call off the main thread. JSON listing of [dir] ("" = home). */
    fun listDir(dir: String): String = (go ?: throw IllegalStateException("연결되어 있지 않습니다")).listDir(dir)

    /** Blocking; call off the main thread. */
    fun readFile(path: String, maxBytes: Long): core.FileData =
        (go ?: throw IllegalStateException("연결되어 있지 않습니다")).readFile(path, maxBytes)

    /**
     * Blocking; call off the main thread. Returns the new modification time. Throws with a
     * message starting with [CONFLICT] when the file changed on the server since
     * it was read ([expectModTime] <= 0 skips that check).
     */
    fun writeFile(path: String, data: ByteArray, expectModTime: Long, expectSize: Long): Long =
        (go ?: throw IllegalStateException("연결되어 있지 않습니다")).writeFile(path, data, expectModTime, expectSize)

    /** Blocking. The folder the shell (tmux pane) is in, or null. */
    fun currentDir(): String? {
        val out = execQuiet(Completion.cwdCommand(tmuxName(), "~")) ?: return null
        return out.lineSequence().firstOrNull { it.startsWith("CWD:") }?.removePrefix("CWD:")?.trim()?.ifBlank { null }
    }

    fun stopForward(f: PortForward) {
        go?.stopForward(f.localPort.toLong())
        _forwards.value = _forwards.value.filterNot { it.localPort == f.localPort }
    }
}

class SessionManager(private val app: App) {
    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions

    fun get(id: String): TermSession? = _sessions.value.firstOrNull { it.id == id }

    /** Reuses an open session for the same host, otherwise creates one (connects once the view reports its size). */
    @Synchronized
    fun open(host: Host): TermSession {
        _sessions.value.firstOrNull { it.host.id == host.id && it.label == null }?.let {
            it.updateHost(host)
            if (it.state.value == TermSession.State.CLOSED) it.connect()
            return it
        }
        val s = TermSession(host, app)
        _sessions.value = _sessions.value + s
        SessionService.update(app, _sessions.value.size)
        return s
    }

    /** Opens an additional session for [host] that runs [command] once (does not replace the main one). */
    @Synchronized
    fun openExtra(host: Host, command: String, label: String): TermSession {
        val s = TermSession(host, app, command, label)
        _sessions.value = _sessions.value + s
        SessionService.update(app, _sessions.value.size)
        return s
    }

    private val terminals = HashMap<String, SessionTerminal>()

    /** The session's terminal, created on first use. Main thread only. */
    fun terminal(s: TermSession): SessionTerminal = terminals.getOrPut(s.id) { SessionTerminal(app, s) }

    @Synchronized
    fun close(s: TermSession) {
        app.mainExecutor.execute { terminals.remove(s.id)?.destroy() }
        s.disconnect()
        _sessions.value = _sessions.value.filterNot { it.id == s.id }
        SessionService.update(app, _sessions.value.size)
    }

    fun next(after: TermSession, delta: Int): TermSession? {
        val list = _sessions.value
        if (list.size < 2) return null
        val i = list.indexOfFirst { it.id == after.id }
        return list[((i + delta) % list.size + list.size) % list.size]
    }

    fun onHostChanged(h: Host) = _sessions.value.forEach { it.updateHost(h) }

    fun onNetworkAvailable() = _sessions.value.forEach { it.onNetworkAvailable() }
}
