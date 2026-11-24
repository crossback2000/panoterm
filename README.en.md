# PanoTerm

[한국어](README.md) | **English**

PanoTerm is an Android SSH client built for running AI coding agents such as Claude Code from a phone. It embeds a Tailscale node so you can reach servers on your tailnet without a separate VPN, and it is designed to make wide terminal output (tables, long logs) readable on a phone screen.

- **License**: MIT (open source)
- **Platform**: Android 12 (API 31) or later, arm64
- **UI languages**: Korean, English (chosen by the device setting or in the app)
- **Distribution**: [GitHub Releases](../../releases) — signed APK with in-app automatic updates

No ads, payments, accounts, cloud sync or usage analytics. All data stays on the device.

---

## Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Security](#security)
4. [Installation and setup](#installation-and-setup)
5. [Recommended server configuration](#recommended-server-configuration)
6. [Keyboard and shortcuts](#keyboard-and-shortcuts)
7. [Update policy](#update-policy)
8. [Building from source](#building-from-source)
9. [CI/CD and release process](#cicd-and-release-process)
10. [Project layout](#project-layout)
11. [Known limitations](#known-limitations)
12. [License and trademarks](#license-and-trademarks)

---

## Features

### Readable wide output

| Feature | Description |
|---|---|
| **Wide view** (`↔`) | Tells the server the terminal is 120, 160 or 200 columns wide, draws it at a readable font size, and lets you pan sideways. Tables and aligned output never wrap. |
| **Table lens** (`▦`) | Finds box-drawing tables (thin, heavy and double lines) and Markdown tables on screen and in the scrollback, and shows them as native tables with a frozen first column, horizontal scrolling and Markdown copy. |
| **Reader mode** (`📖`) | Shows the agent's last reply in large type; tables are unfolded into `▸ field: value` lists per row. |
| **Search output** (`🔍`) | Finds text in past output and jumps to it (▲ older, ▼ newer). Inside tmux it searches tmux's whole history with tmux's copy-mode search. An all-lowercase query ignores case. |
| **Bundled D2Coding font** | Hangul is exactly two Latin cells wide, so tables mixing Korean text stay aligned. |
| **GPU rendering** | Renders with WebGL by default and falls back to DOM on devices without it. Pixel-smooth inertial scrolling. |

### AI agent workflow

- **Approval buttons**: when a prompt such as "Do you want to proceed?" appears, large buttons (1 / 2 / 3 / Esc) are shown.
- **Activity tabs**: each session and tmux window shows working (blue), waiting for input or approval (orange) or idle (green); tap to switch.
- **Notifications**: an Android notification when the agent waits for input while you are in another app (Claude Code hook or terminal bell).
- **Autocomplete**: suggests `/` commands in the Claude prompt, custom commands (`~/.claude/commands`), skills, `@` files and `cd` paths in the shell. Server lookups run on a separate SSH channel and never mix with terminal input.
- **Command palette** (`⚡`): sends commands such as `/compact`, `/clear` or `claude --continue` in one tap. The list is editable.
- **File browser** (`📁`): an SFTP browser that opens in the terminal's current folder, with a syntax-highlighted viewer and a simple editor. It asks before overwriting a file that changed on the server, saves by atomic replace, and opens `.env` files with values masked.
- **Image hand-off**: uploads a photo or screenshot to the server and types its path into the prompt.
- **Port-forward preview**: opens a dev server on the server (e.g. `localhost:3000`) in the phone browser. Closes by itself after 30 minutes unused.

### Connectivity and session continuity

- **Embedded Tailscale** (tsnet, userspace): does not take the Android VPN slot, so it works alongside other VPNs. After login, add hosts straight from the tailnet device list.
- **Direct connections**: turn off "Connect over Tailscale (tailnet)" in the host editor to reach a public IP or a server on the same Wi-Fi (e.g. `192.168.0.10`) without Tailscale. Host key checks and public-key authentication apply the same way.
- **tmux auto-attach**: attaches to the configured tmux session on connect. If tmux is missing, it can install it with the server's package manager (apt, dnf, yum, pacman, zypper, apk, brew, pkg).
- **Keepalive and auto-reconnect**: probes every 15 seconds and treats the link as lost only after 3 consecutive unanswered probes (like OpenSSH `ServerAliveCountMax`). Reconnects with exponential backoff after network changes or drops.
- **Background operation**: a foreground service and wake lock are held only while sessions are open.
- **Home-screen shortcuts** for frequently used hosts.

### Input

- The phone keyboard composes text exactly as in a normal text field; only committed text is sent to the server. Samsung Keyboard, Gboard, Cheonjiin and Japanese input all follow the same rules.
- Bluetooth hardware keyboard shortcuts (tuned for the Logitech K380), CapsLock as Ctrl, volume key as Ctrl.
- The on-screen key row is configurable, and the editor explains what each key does.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Android app (Kotlin, Jetpack Compose)                        │
│  ├─ UI · keyboard input (ImeMirror) · notifications · service │
│  ├─ Terminal rendering: xterm.js (WebView, WebGL/DOM)         │
│  └─ Key storage: Android Keystore (StrongBox / TEE)           │
│                    ↕ JNI (gomobile bind)                      │
│ Go core (core.aar)                                            │
│  ├─ tsnet           embedded Tailscale (userspace networking) │
│  ├─ x/crypto/ssh    SSH sessions, PTY, keepalive, forwarding  │
│  └─ pkg/sftp        file browser, uploads, atomic saves       │
└──────────────────────────────────────────────────────────────┘
```

See [DESIGN.en.md](DESIGN.en.md) for the detailed design.

---

## Security

| Area | Implementation |
|---|---|
| Authentication | SSH public-key authentication only (no password authentication). |
| Private keys | Ed25519 keys are encrypted with an AES-GCM key held in Android Keystore and stored in app-private storage. |
| Hardware keys | Optionally an EC P-256 key generated in StrongBox (or the TEE) that never leaves the chip. With "fingerprint on every use", the chip will not sign without biometric confirmation. |
| Server verification | Host key fingerprints are confirmed on first connect (TOFU), with a warning if a key changes. |
| App lock | Biometric confirmation can be required when opening the app and when opening a new session. |
| Backup | Exported files are encrypted with PBKDF2-HMAC-SHA256 (600,000 iterations) + AES-256-GCM. Hardware keys are not included. |
| Updates | A downloaded APK is installed only if its signing certificate matches the installed app and its SHA-256 matches the release metadata. |
| Clipboard | Server-to-phone clipboard copy (OSC 52) is off by default. OSC 8 links show the real address and ask before opening. |
| Data collection | No analytics or telemetry. The only outbound call besides your SSH traffic is the update check (GitHub API), which can be turned off. |

Please report security vulnerabilities privately through the repository's **Security tab → [Report a vulnerability](../../security/advisories/new)** instead of a public issue. Reports are visible only to the maintainers.

---

## Installation and setup

1. Download the latest `PanoTerm-x.y.z.apk` from [Releases](../../releases) and install it (allow installs from unknown sources).
2. Tap **Tailscale login** and approve the device in the browser.
3. Under **SSH keys**, generate a key and add the public key to `~/.ssh/authorized_keys` on the server.
4. In the tailnet device list, tap **+ Add**, choose the user and key, and save.
5. Confirm the server's host key fingerprint on first connect.

**Language**: the app follows the device language (Korean for Korean, English otherwise). On Android 13+ you can change it under **Settings → Language** or in the system's per-app language setting.

**Moving to a new phone**: Settings → Backup → Export saves hosts, keys, server fingerprints and commands into one encrypted file; import it on the new device.

---

## Recommended server configuration

### tmux

`~/.tmux.conf`:

```
set -g mouse on
set -g extended-keys on          # pass extended keys such as Shift+Enter
set -g set-clipboard on          # copy on the server to the phone clipboard (also enable it in the app)
set -g allow-passthrough on      # let notification sequences through tmux
set -g history-limit 50000
set -ga update-environment MOBILE_TERM
```

Besides the standard paths, tmux is looked up in Homebrew (`/opt/homebrew/bin`), linuxbrew, `~/.local/bin` and nix paths. If it is not found you get a plain shell, and the **Install tmux** button in the warning banner can install it. On Windows servers, run tmux inside WSL.

### Claude Code

Connections from the app set `MOBILE_TERM=1`. Add this to `~/.claude/CLAUDE.md` to get narrow, phone-friendly answers:

```
- If the environment variable MOBILE_TERM is set, I'm on a phone: avoid wide tables,
  prefer short bullet lists and keep lines under ~60 characters.
```

### Notifications

Copy [`extras/claude-notify.sh`](extras/claude-notify.sh) to `~/.claude/` (`chmod +x`) and register it as a hook in `~/.claude/settings.json` as described in the file.

---

## Keyboard and shortcuts

Tuned for the Logitech K380. Lock Fn with Fn+Esc so F1–F12 work directly.

| Key | Action |
|---|---|
| Shift+Tab | Switch Claude Code mode (`ESC [ Z`) |
| Shift+Enter, Alt+Enter | New line without sending |
| Ctrl+Backspace | Delete word |
| Shift+Space | Switch Korean/English (follows the phone keyboard's setting) |
| Ctrl+Shift+V / C | Paste / copy |
| Ctrl+Shift+F | Table lens |
| Ctrl+Shift+R | Reader mode |
| Ctrl+Shift+W | Toggle wide view |
| Ctrl+Shift+P | Command palette |
| Ctrl+Shift+E | File browser (Ctrl+S saves while editing) |
| Ctrl+Shift+S | Search output (Enter older match, Shift+Enter newer, Esc closes) |
| Ctrl+Alt+= / − | Font size |
| Ctrl+Alt+T | Show/hide tmux key row |
| Ctrl+Tab | Next session |
| CapsLock | Use as Ctrl (enable in Settings) |

Without a hardware keyboard, use the on-screen key row. The default set is Tab, Esc, cursor pad (`◀ ▶`), arrows, command palette, `/`, ⌫ and Enter. Under Settings → Edit on-screen key row you can add ⇧Tab, double Esc, ^C, Ctrl/Alt, symbol keys and more, and reorder them.

---

## Update policy

- The app checks GitHub releases when it opens (at most once every 6 hours).
- With automatic updates on, new versions are downloaded and installed, on Wi-Fi only by default.
- If sessions are open, installation waits until they are all closed (installing restarts the app).
- The first time, "Install unknown apps" must be allowed for PanoTerm.

---

## Building from source

### Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform `android-37.2`, build-tools 37.0.0 |
| Android NDK | 30.0.16248370 (requires an x86_64 host) |
| Go | as in `core/go.mod` (currently 1.27) |

### Build

```bash
bash scripts/build-core.sh        # Go core → app/libs/core.aar (Windows: scripts/build-core.ps1)
./gradlew assembleDebug           # debug build
./gradlew assembleRelease         # release build (needs signing config)
```

Release signing is read from `~/.panoterm/keystore.properties` outside the repository (or the file named by `PANOTERM_KEYSTORE_PROPS`):

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=panoterm
keyPassword=...
```

### Tests

```bash
cd core && go test ./...          # in-process SSH server: connect, keepalive, SFTP, forwarding
./gradlew testDebugUnitTest       # input (IME), table detection, output scanning, backup crypto, ...
node --test app/src/test/js/*.test.js   # table detection and reader mode (JavaScript)
```

### Localization

UI strings live in `app/src/main/res/values/strings.xml` (English, default) and `values-ko/strings.xml` (Korean). To add a language, add `values-<language code>/strings.xml`; the Android 13+ per-app language list is generated at build time.

### Third-party assets

xterm.js, D2Coding and license texts are checked in. Run `scripts/fetch-assets.ps1` when upgrading them, and `scripts/gen-go-licenses.ps1` when Go dependencies change.

---

## CI/CD and release process

| Workflow | Trigger | What it does |
|---|---|---|
| `CI` | push to `main`, every PR | Go tests, JavaScript tests, Go core build, app unit tests, debug build. Uses no secrets, so it is safe for fork PRs. |
| `Release` | `vX.Y.Z` tag | Signed APK build, signing-certificate check, SHA-256, GitHub release |

Release steps:

1. Open a PR from a feature branch and merge into `main` once CI passes.
2. Write `release-notes/vX.Y.Z.md`, bump the version in `app/build.gradle.kts`, and commit.
3. `git tag vX.Y.Z && git push origin vX.Y.Z` → the `Release` workflow publishes the APK and installed apps pick it up through auto-update.

The signing key is not in the repository; it exists only as encrypted Actions secrets (`PANOTERM_KEYSTORE_B64`, `PANOTERM_STORE_PASSWORD`, `PANOTERM_KEY_ALIAS`, `PANOTERM_KEY_PASSWORD`). The release workflow fails if the APK's signing certificate differs from `EXPECTED_CERT_SHA256`, because a changed signature would stop existing installs from updating. To publish your own fork, register your own key in the secrets and change that value.

---

## Project layout

```
core/                    Go core (gomobile → app/libs/core.aar)
  node.go                Tailscale (tsnet)
  ssh.go, extras.go      SSH sessions, keepalive, SFTP upload, port forwarding
  files.go               file browser (SFTP listing, reading, atomic saves)
  keys.go, hwkey.go      key generation/import, hardware-key signing
  netenv*.go             Android network information bridge
app/src/main/
  java/dev/panoterm/     Android app (Kotlin, Compose)
    term/                terminal sessions, input handling, autocomplete, output scanning
    ui/                  screens (hosts, terminal, file browser, settings, ...)
    data/                settings, hosts, key store, backup
    net/                 Tailscale state, updates
  res/values*/           UI strings (English default, Korean)
  assets/term/           xterm.js page, table detection, D2Coding
  assets/licenses/       open source license texts
extras/                  server-side scripts (Claude notifications)
scripts/                 build, asset and license scripts
release-notes/           per-version change notes
```

---

## Known limitations

- Android versions below 12 and 32-bit devices are not supported. Release APKs contain arm64 only.
- The Android NDK ships for x86_64 hosts only, so APKs can't be built on ARM Linux (CI builds them).
- Mosh is not supported; sessions are kept alive with tmux and automatic reconnects.
- Per-app language selection requires Android 13+. Android 12 follows the device language.
- Release notes (`release-notes/`) are written in Korean.

---

## License and trademarks

PanoTerm source code is released under the [MIT License](LICENSE). Bundled third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.en.md](THIRD_PARTY_NOTICES.en.md) and are also viewable in the app under **Settings → Open source licenses**.

Tailscale is a trademark of Tailscale Inc., WireGuard of Jason A. Donenfeld, and Claude of Anthropic. PanoTerm is not affiliated with or endorsed by these companies.
