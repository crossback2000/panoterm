package dev.panoterm.term

import android.util.Base64

/**
 * Watches the raw output stream (independently of the WebView, so it also
 * works while the app is in the background) for:
 *  - BEL (0x07) outside of OSC strings  → Claude Code "terminal_bell" notifications
 *  - OSC 9 ; text                       → iTerm2-style notification (Claude Code "iterm2" channel)
 *  - OSC 777 ; notify ; title ; body    → rxvt/kitty-style notification
 *  - OSC 52 ; c ; base64                → set clipboard from the server (tmux set-clipboard, nvim, ...)
 */
class OutputScanner(
    private val onBell: () -> Unit,
    private val onNotify: (title: String, body: String) -> Unit,
    private val onClipboard: (String) -> Unit,
) {
    private enum class St { NORMAL, ESC, OSC, OSC_ESC }

    private var st = St.NORMAL
    private val osc = java.io.ByteArrayOutputStream()

    fun feed(b: ByteArray) {
        for (x in b) {
            val c = x.toInt() and 0xff
            when (st) {
                St.NORMAL -> when (c) {
                    0x1b -> st = St.ESC
                    0x07 -> onBell()
                    0x9d -> { osc.reset(); st = St.OSC } // C1 OSC (rare)
                }
                St.ESC -> st = if (c == ']'.code) { osc.reset(); St.OSC } else St.NORMAL
                St.OSC -> when (c) {
                    0x07 -> { finish(); st = St.NORMAL }
                    0x1b -> st = St.OSC_ESC
                    else -> if (osc.size() < 1_000_000) osc.write(c) else st = St.NORMAL
                }
                St.OSC_ESC -> if (c == '\\'.code) { finish(); st = St.NORMAL } else { st = St.NORMAL }
            }
        }
    }

    private fun finish() {
        val s = osc.toString(Charsets.UTF_8.name())
        val semi = s.indexOf(';')
        if (semi < 0) return
        val code = s.substring(0, semi)
        val rest = s.substring(semi + 1)
        when (code) {
            "9" -> {
                // "9;4;..." is ConEmu progress, not a notification.
                if (!rest.matches(Regex("^[0-9];.*"))) onNotify("", rest)
            }
            "777" -> {
                val p = rest.split(';', limit = 3)
                if (p.firstOrNull() == "notify") onNotify(p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            }
            "52" -> {
                val data = rest.substringAfter(';', "")
                if (data.isNotEmpty() && data != "?") {
                    runCatching { String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8) }
                        .getOrNull()?.let(onClipboard)
                }
            }
        }
    }
}
