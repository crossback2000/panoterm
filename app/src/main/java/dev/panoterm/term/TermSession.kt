package dev.panoterm.term

import dev.panoterm.App
import dev.panoterm.data.Host
import dev.panoterm.data.KnownHosts
import core.ConnectOptions
import core.Core
import core.HostKeyVerifier
import core.Session
import core.SessionListener
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

class TermSession(val host: Host, private val app: App) {
    enum class State { IDLE, CONNECTING, CONNECTED, CLOSED }

    val id: String = UUID.randomUUID().toString()
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    @Volatile var appCursor = false
    @Volatile var bracketedPaste = false

    @Volatile private var go: Session? = null
    @Volatile private var generation = 0
    private val lock = Any()
    private val ring = ByteRing(1 shl 20)
    private var sink: ((ByteArray) -> Unit)? = null
    private val writer = Executors.newSingleThreadExecutor()
    private var cols = 80
    private var rows = 24

    // ---- output ----

    private fun emit(b: ByteArray) = synchronized(lock) {
        ring.write(b)
        sink?.invoke(b)
    }

    private fun note(text: String) = emit("\r\n\u001b[33m[PanoTerm] $text\u001b[0m\r\n".toByteArray())

    /** Replays buffered output, then streams new output to [s] (called on the Go reader thread). */
    fun attach(s: (ByteArray) -> Unit) = synchronized(lock) {
        val snap = ring.snapshot()
        if (snap.isNotEmpty()) s(snap)
        sink = s
    }

    fun detach() = synchronized(lock) { sink = null }

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
        _state.value = State.CONNECTING
        _message.value = null
        val gen = ++generation
        note("${host.user}@${host.address}:${host.port} 연결 중...")
        thread(name = "ssh-connect") {
            var pem: ByteArray? = null
            try {
                val keyId = host.keyId ?: throw IllegalStateException("이 호스트에 SSH 키가 지정되지 않았습니다")
                val node = if (host.useTailnet) {
                    waitForTailscale()
                    app.tailscale.nodeOrNull() ?: throw IllegalStateException("Tailscale이 시작되지 않았습니다")
                } else null
                pem = app.keys.privateKey(keyId)
                val h = host // ConnectOptions also has a `host` property; avoid shadowing inside apply
                val opts = ConnectOptions().apply {
                    setHost(h.address.trim())
                    setPort(h.port.toLong())
                    setUser(h.user.trim())
                    setPrivateKey(pem)
                    setUseTailnet(h.useTailnet)
                    setCols(cols.toLong())
                    setRows(rows.toLong())
                    setCommand(startupCommand())
                    setEnv("MOBILE_TERM=1\nLANG=C.UTF-8")
                }
                val s = Core.connect(node, opts, verifier, listener(gen))
                if (gen != generation) { s.close(); return@thread }
                go = s
                _state.value = State.CONNECTED
                // Size may have changed while connecting.
                writer.execute { runCatching { s.resize(cols.toLong(), rows.toLong()) } }
            } catch (e: Exception) {
                if (gen == generation) {
                    val msg = e.message ?: e.toString()
                    _message.value = msg
                    _state.value = State.CLOSED
                    note("연결 실패: $msg")
                }
            } finally {
                pem?.fill(0)
            }
        }
    }

    fun disconnect() {
        generation++
        go?.close()
        go = null
        _state.value = State.CLOSED
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

    private fun startupCommand(): String {
        if (!host.tmux) return ""
        val name = host.tmuxSession.ifBlank { "main" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        // POSIX sh. Falls back to a login shell if tmux is missing.
        return "export MOBILE_TERM=1; " +
            "if command -v tmux >/dev/null 2>&1; then exec tmux -u new -A -s $name; " +
            "else echo '[PanoTerm] tmux not found - starting login shell'; exec \"\${SHELL:-/bin/sh}\" -l; fi"
    }

    private fun listener(gen: Int) = object : SessionListener {
        override fun onData(data: ByteArray) {
            if (gen == generation) emit(data)
        }

        override fun onClosed(reason: String) {
            if (gen != generation) return
            go = null
            _message.value = reason
            _state.value = State.CLOSED
            note("연결 종료: $reason")
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
}

class SessionManager(private val app: App) {
    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions

    fun get(id: String): TermSession? = _sessions.value.firstOrNull { it.id == id }

    /** Reuses an open session for the same host, otherwise creates one (connects once the view reports its size). */
    @Synchronized
    fun open(host: Host): TermSession {
        _sessions.value.firstOrNull { it.host.id == host.id && it.state.value != TermSession.State.CLOSED }?.let { return it }
        _sessions.value.filter { it.host.id == host.id }.forEach { close(it) }
        val s = TermSession(host, app)
        _sessions.value = _sessions.value + s
        return s
    }

    @Synchronized
    fun close(s: TermSession) {
        s.disconnect()
        _sessions.value = _sessions.value.filterNot { it.id == s.id }
    }
}
