# Third-Party Notices

[한국어](THIRD_PARTY_NOTICES.md) | **English**

PanoTerm itself is licensed under the [MIT License](LICENSE). Below are the third-party software and fonts included in or linked into PanoTerm, with their licenses.

Full license texts are in [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/). They are **shipped inside the APK** and viewable in the app under **Settings → Open source licenses** (`ui/LicensesScreen.kt`).

> All components use permissive licenses (MIT / BSD / Apache-2.0 / OFL). There are no licenses with source-disclosure obligations such as the GPL (`scripts/gen-go-licenses.ps1` checks this on every build).

## 1. Bundled in the app

| Component | Version | License | Copyright | Location | License file |
|---|---|---|---|---|---|
| [D2Coding](https://github.com/naver/d2codingfont) font (Regular, Bold) | 1.3.2 (2018-05-24) | SIL Open Font License 1.1 | © 2015 NAVER Corporation, Reserved Font Name "D2Coding", "D2Coding-Bold" | `assets/term/fonts/` | `D2Coding.OFL.txt` (+ `fonts/OFL.txt`) |
| [xterm.js](https://github.com/xtermjs/xterm.js) `@xterm/xterm` | 6.0.0 | MIT | © 2017-2019 The xterm.js authors, © 2014-2016 SourceLair Private Company, © 2012-2013 Christopher Jeffrey | `assets/term/xterm.js`, `xterm.css` | `xterm.LICENSE.txt` |
| `@xterm/addon-fit` | 0.11.0 | MIT | © 2019 The xterm.js authors | `assets/term/addon-fit.js` | `xterm-addon-fit.LICENSE.txt` |
| `@xterm/addon-unicode11` | 0.9.0 | MIT | © 2019 The xterm.js authors | `assets/term/addon-unicode11.js` | `xterm-addon-unicode11.LICENSE.txt` |
| `@xterm/addon-web-links` | 0.12.0 | MIT | © 2017 The xterm.js authors | `assets/term/addon-web-links.js` | `xterm-addon-web-links.LICENSE.txt` |
| `@xterm/addon-webgl` | 0.19.0 | MIT | © 2018 The xterm.js authors | `assets/term/addon-webgl.js` | `xterm-addon-webgl.LICENSE.txt` |

**D2Coding (OFL) terms in brief**: may be bundled and redistributed with software; the full license must accompany it; **the font may not be sold on its own**; a modified font may not use the name "D2Coding". PanoTerm uses the original files unmodified.

## 2. Statically linked into the Go core (`core.aar`)

Main components:

| Component | License | Copyright | License file |
|---|---|---|---|
| [Tailscale](https://github.com/tailscale/tailscale) (`tailscale.com`, tsnet) | BSD-3-Clause | © 2020 Tailscale Inc & contributors | `tailscale.LICENSE.txt` |
| wireguard-go (Tailscale fork) | MIT | © WireGuard LLC | `wireguard-go.LICENSE.txt` |
| gVisor (netstack, via Tailscale) | Apache-2.0 | © The gVisor Authors | `go/gvisor.dev/...` |
| Go standard library and runtime | BSD-3-Clause | © 2009 The Go Authors | `go.LICENSE.txt` |
| `golang.org/x/crypto` (SSH) | BSD-3-Clause | © The Go Authors | `golang.org-x-crypto.LICENSE.txt` |
| `golang.org/x/mobile` (gomobile bindings) | BSD-3-Clause | © The Go Authors | `golang.org-x-mobile.LICENSE.txt` |
| [`github.com/pkg/sftp`](https://github.com/pkg/sftp) (file browser, uploads) | BSD-2-Clause | © 2013 Dave Cheney | `pkg-sftp.LICENSE.txt` |

**Full list (36 modules including indirect dependencies)**: [`go-modules.csv`](app/src/main/assets/licenses/go-modules.csv); full texts in [`licenses/go/`](app/src/main/assets/licenses/go/).
Summary: BSD-3-Clause 20 · MIT 8 · Apache-2.0 6 · BSD-2-Clause 2 — generated with [`go-licenses`](https://github.com/google/go-licenses); nothing restricted, forbidden or unknown.

## 3. Android libraries (Gradle dependencies)

All under the **Apache License 2.0** (`Apache-2.0.txt`):

| Component | Copyright |
|---|---|
| AndroidX Core, Activity, Fragment, Lifecycle, Biometric | © The Android Open Source Project |
| Jetpack Compose (UI, Foundation, Material 3) | © The Android Open Source Project |
| Kotlin standard library | © JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines | © JetBrains s.r.o. and contributors |

Test-only (not in the APK): JUnit 4 (EPL-1.0).

## 4. Trademarks

- "Tailscale" is a trademark of Tailscale Inc. PanoTerm is not affiliated with or endorsed by Tailscale Inc., and does not use the Tailscale logo in its name or icon.
- "WireGuard" is a registered trademark of Jason A. Donenfeld.
- "Claude" is a trademark of Anthropic, mentioned for descriptive purposes only.
- "D2Coding" is a Reserved Font Name of NAVER Corporation.

## 5. Rules for adding dependencies

When adding a library, font or asset, update these **in the same commit**:

1. This document and the Korean version ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))
2. `app/src/main/assets/licenses/` (add the full text — and its URL to `scripts/fetch-assets.ps1`)
3. `app/src/main/java/dev/panoterm/ui/LicensesScreen.kt`

When Go dependencies change, run `scripts/gen-go-licenses.ps1`.
