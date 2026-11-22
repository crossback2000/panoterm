# PanoTerm Design

[한국어](DESIGN.md) | **English**

This document describes PanoTerm's structure, its main design decisions and the reasons behind them. For usage, see [README.en.md](README.en.md).

---

## 1. Goals and scope

### Goals

1. **A mobile terminal built for running AI agents**: make long-running tools with wide output, such as Claude Code, reliable to drive and easy to read from a phone.
2. **Private-network access without a separate VPN**: embed Tailscale so tailnet servers are reachable directly.
3. **Local-only data**: no accounts, cloud sync or analytics; everything is stored encrypted on the device.
4. **Permissive licenses only**: MIT, BSD, Apache-2.0 and OFL components, so distribution and reuse are unrestricted.

### Out of scope

- Password-based SSH authentication
- Cloud sync and team-wide host sharing
- Mosh (covered by tmux and automatic reconnects)

### Reference environment

Tuned on a Galaxy S25 Ultra with a Logitech K380 Bluetooth keyboard. Supported: Android 12 (API 31) or later on arm64.

---

## 2. Architecture

```
┌─ Kotlin + Jetpack Compose ──────────────────────────────────────┐
│  ui/      screens, key row, overlays (table lens, reader,       │
│           approval buttons)                                     │
│  term/    TermSession, input (InputCore · ImeMirror),           │
│           autocomplete, output scanning (OutputScanner ·        │
│           AgentStatus)                                          │
│  data/    hosts, settings, key store (Keystore), backup         │
│  net/     Tailscale state, updates                              │
│  assets/term/  xterm.js page (rendering, wide view, tables)     │
├─────────────────────── JNI (gomobile bind) ─────────────────────┤
│  Go core (core.aar)                                             │
│   node.go     tsnet: userspace Tailscale node                   │
│   ssh.go      SSH sessions, PTY, keepalive                      │
│   extras.go   SFTP upload, port forwarding (auto-close on idle) │
│   files.go    listing, reading, atomic saves                    │
│   keys.go · hwkey.go  key generation/import, delegated signing  │
└─────────────────────────────────────────────────────────────────┘
```

### Key decisions

| Decision | Rationale |
|---|---|
| Networking and SSH in a Go core | tsnet (Tailscale), `x/crypto/ssh` and `pkg/sftp` are mature in Go, and gomobile packages them into a single AAR. |
| tsnet in userspace mode | No Android VpnService, so it coexists with other VPNs and the official Tailscale app. |
| xterm.js (WebView) for rendering | Fixed column counts, visual zoom, horizontal scrolling and Unicode 11 widths are straightforward, and it is MIT licensed. The Termux terminal library targets local processes and is GPLv3, so it was not used. |
| Key input handled in Kotlin | IME composition, hardware keys and the key row are handled consistently in one place; the WebView only renders. |
| Sessions own their terminal (`TermSession`) | Terminal state and the connection survive screen recreation and session switches. |

---

## 3. Wide output

The core idea is to **decouple** the PTY column count from the on-screen font size.

1. **Wide view**: the server is told a fixed column count (120/160/200, per host); the screen renders at a readable size and pans horizontally, following the cursor.
2. **Pinch zoom is visual only**: the PTY size does not change, so the agent's screen is not redrawn.
3. **Table lens**: finds box-drawing tables (thin, heavy, double lines, heavy header rules) and Markdown tables (including short alignment rows) on screen and in the scrollback. Tables broken by line wrapping are reassembled and shown natively (frozen first column, horizontal scroll). The logic lives in `assets/term/tables.js` and is covered by Node tests.
4. **Reader mode**: strips ANSI from the last reply and reflows it to the screen width; tables are unfolded into per-row lists.
5. **Server-side hint**: `MOBILE_TERM=1` is set on connect so the agent can be asked to answer in a phone-friendly format.
6. **Font**: bundles D2Coding (OFL 1.1) with exact CJK double-width and box-drawing widths.

---

## 4. Input

### Phone keyboard (IME)

- The keyboard is shown a real text buffer (`ImeMirror`) and edits it like any text field.
- After each edit, the **committed** part (outside the composing region) is compared with what was already sent, and only the difference goes out: removed characters as DEL, new ones as text.
- The composition in progress (한 → 하 → ㅎ) is never sent; it is only drawn at the cursor.
- Input that bypasses the text field (key events, key row, paste) first sends the composition and clears the buffer. The keyboard is told the field was cleared, as a normal text field would, and is **never restarted** (Samsung Keyboard falls back to English after a restart).
- When the keyboard passes ⌫ on as a key event, one character is removed from the buffer the way a text field's key listener would, and the composition stays with the keyboard.
- This logic lives in Android-free `InputCore` / `ImeMirror` and is verified by unit tests that replay keyboard behaviour.

### Hardware keyboard

- Hardware keys reach the IME first; only keys it passes on are handled in `dispatchKeyEvent`.
- xterm sequences: Shift+Tab → `ESC [ Z`, Alt → ESC prefix, F1–F12, Home/End/PgUp/PgDn, Shift/Alt+Enter → `ESC CR` (new line).
- CapsLock-as-Ctrl applies only while held and is released when focus is lost, so Ctrl never sticks.
- Korean/English switching belongs to the phone keyboard. The in-app Hangul automaton is an opt-in fallback for keyboards that can't compose Hangul from a hardware keyboard (off by default).

### On-screen key row

- Order and contents are configurable; the editor explains each key.
- Ctrl/Alt are sticky modifiers applied to the next character.
- Gestures: drag the cursor pad for repeated arrows, two-finger swipe left for Esc, (optional) volume down as Ctrl.

---

## 5. Connection lifecycle

| Stage | Behaviour |
|---|---|
| Connect | wait for tsnet → SSH handshake → host key check (TOFU) → PTY request → tmux attach script |
| tmux | looks for tmux on standard paths plus Homebrew, linuxbrew, `~/.local/bin` and nix paths, then attaches with `tmux new -A -s <session>`. Without tmux it falls back to a plain shell and offers to install it. |
| Keepalive | sends `keepalive@openssh.com` every 15 seconds with at most one probe in flight, and disconnects only after 3 consecutive unanswered intervals (~45–60 s), so short mobile stalls (Wi-Fi↔LTE handover, Doze) don't drop the session. |
| Reconnect | retries at 1, 2, 4 … up to 30 seconds apart, at most 30 times, and immediately when the network returns. No reconnect after a user-initiated disconnect. |
| Background | a foreground service runs while sessions are open; a partial wake lock is held with a 2-hour timeout and renewed hourly, and released when the last session closes. |

---

## 6. Side channels and state detection

- **Side SSH channel**: autocomplete (folder listings, custom commands), current-directory lookup and tmux window status use separate exec channels, so nothing is typed into the terminal.
- **Output scanning**: `OutputScanner` handles the terminal bell, OSC 9/777 notifications and OSC 52 clipboard. `AgentStatus` derives working / waiting / idle from the screen contents.
- **Approval prompts**: questions such as "Do you want to proceed?" with numbered options are detected and shown as native buttons.

---

## 7. Data and security

| Data | Storage |
|---|---|
| Hosts, settings, commands | JSON in app-private storage |
| Ed25519 private keys | encrypted with an Android Keystore AES-GCM key (`SecretBox`) |
| Hardware keys | non-exportable EC P-256 in StrongBox (or the TEE); optional biometric check on every use |
| known_hosts | app-private storage; a changed key triggers a warning and requires explicit replacement |
| Backup files | `PANOBAK2` format: PBKDF2-HMAC-SHA256 (600,000 iterations) → AES-256-GCM with the magic as AAD; hardware keys excluded |

**Update verification**: an APK from GitHub releases is installed through `PackageInstaller` only after checking (1) a size limit, (2) its SHA-256, (3) the package name and (4) that its signing certificate matches the installed app.

**Clipboard and links**: server-to-phone clipboard (OSC 52) is off by default. OSC 8 links show the real host rather than the link text and ask for confirmation.

---

## 8. Localization

- UI strings are Android resources: `res/values` is English (default) and `res/values-ko` is Korean.
- Composables use `stringResource` / `LocalResources`; code outside composition (notifications, services, terminal notes) uses `str()`.
- On Android 13+, `generateLocaleConfig` produces the per-app language list, and the app's settings switch languages through `LocaleManager`.
- Messages embedded in shell scripts sent to the server have quotes, `$`, backslashes and backticks removed so they can't break the `sh -c '…'` quoting.
- Errors from pure logic used in unit tests are expressed as `UiException(string id)`, so it can be tested without Android.

---

## 9. Testing strategy

| Layer | Tool | Coverage |
|---|---|---|
| Go core | `go test` (in-process SSH server) | connect, PTY, environment, window size, SFTP, port forwarding, host key rejection, hardware-key signing, keepalive tolerance and timeout |
| Kotlin | JUnit (`testDebugUnitTest`) | IME buffer and input core, Hangul automaton, key encoding, output scanning, agent status, autocomplete, paste sanitizing, backup crypto, tmux script quoting |
| JavaScript | `node --test` | table detection, reader mode, approval prompt parsing |
| Manual | real device | IME behaviour (Samsung Keyboard, Gboard), K380, Tailscale login, auto-update |

---

## 10. Licensing rules

- Only permissive licenses (MIT, BSD, Apache-2.0, OFL). Licenses with source-disclosure obligations such as the GPL are blocked by `scripts/gen-go-licenses.ps1`.
- When adding a dependency, update `THIRD_PARTY_NOTICES.md` (and its English version), `app/src/main/assets/licenses/` and `LicensesScreen.kt` **in the same commit**.

---

## 11. Under consideration

- Mosh support (possible, since tsnet supports UDP, but low priority)
- Profiles for DeX and external monitors
- Additional UI languages (just add `values-xx/strings.xml`)
