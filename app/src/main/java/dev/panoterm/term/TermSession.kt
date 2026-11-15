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

/** Fixed-size ring buffer of terminal output, replayed when the terminal view is re-created. */
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

class TermSession(host0: Host, private val app: App) {
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

    @Volatile var appCursor = false
    @Volatile var bracketedPaste = false

    @Volatile private var go: Session? = null
    @Volatile private var generation = 0
    private val lock = Any()
    private val ring = ByteRing(2 shl 20)
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
            if (app.settings.current.notifyOnBell) notifyUser(host.title, "입력을 기다리고 있습니다 (터미널 벨)")
        },
        onNotify = { title, body -> notifyUser(title.ifBlank { host.title }, body.ifBlank { "알림" }) },
        onClipboard = { text ->
            main.post {
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
        ring.write(b)
        sink?.invoke(b)
    }

    private fun note(text: String) = emit("\r\n\u001b[33m[PanoTerm] $text\u001b[0m\r\n".toByteArray())

    /** Replays buffered output, then streams new output to [s]. */
    fun attach(s: (ByteArray) -> Unit) = synchronized(lock) {
        val snap = ring.snapshot()
        if (snap.isNotEmpty()) s(snap)
        sink = s
    }

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
        writer.execute { runCatching { go?.write(b) } }
    }

    fun send(s: String) = send(s.toByteArray())

    fun paste(text: String) {
        val t = text.replace("\r\n", "\r").replace('\n', '\r')
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
        if (!h.tmux) return ""
        val name = h.tmuxSession.ifBlank { "main" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return tmuxCommand(name)
    }

    companion object {
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
        fun tmuxCommand(name: String): String {
            val script = listOf(
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
            ).joinToString("\n")
            return "exec sh -c '$script'"
        }
    }

    private fun listener(gen: Int) = object : SessionListener {
        override fun onData(data: ByteArray) {
            if (gen != generation) return
            scanner.feed(data)
            emit(data)
        }

        override fun onClosed(reason: String) {
            if (gen != generation) return
            go = null
            _forwards.value = emptyList()
            _message.value = reason
            _state.value = State.CLOSED
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
        _sessions.value.firstOrNull { it.host.id == host.id }?.let {
            it.updateHost(host)
            if (it.state.value == TermSession.State.CLOSED) it.connect()
            return it
        }
        val s = TermSession(host, app)
        _sessions.value = _sessions.value + s
        SessionService.update(app, _sessions.value.size)
        return s
    }

    @Synchronized
    fun close(s: TermSession) {
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
