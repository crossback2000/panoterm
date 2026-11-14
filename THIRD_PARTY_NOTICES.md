# Third-Party Notices

PanoTerm 자체는 [MIT 라이선스](LICENSE)입니다. 아래는 PanoTerm에 포함되거나 링크되는 외부 소프트웨어·폰트와 그 라이선스입니다.

라이선스 전문은 [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)에 있으며 **APK에 그대로 포함**되어 앱의 **설정 → 오픈소스 라이선스** 화면(`ui/LicensesScreen.kt`)에서 볼 수 있습니다.

> 사용 중인 모든 구성요소는 허용형 라이선스(MIT / BSD / Apache-2.0 / OFL)입니다. GPL 등 소스 공개 의무 라이선스는 없습니다 (`scripts/gen-go-licenses.ps1`이 빌드 때마다 검사).

## 1. 앱에 직접 포함된 것

| 구성요소 | 버전 | 라이선스 | 저작권 | 포함 위치 | 라이선스 파일 |
|---|---|---|---|---|---|
| [D2Coding](https://github.com/naver/d2codingfont) 폰트 (Regular, Bold) | 1.3.2 (2018-05-24) | SIL Open Font License 1.1 | © 2015 NAVER Corporation, Reserved Font Name "D2Coding", "D2Coding-Bold" | `assets/term/fonts/` | `D2Coding.OFL.txt` (+ `fonts/OFL.txt`) |
| [xterm.js](https://github.com/xtermjs/xterm.js) `@xterm/xterm` | 5.5.0 | MIT | © 2017-2019 The xterm.js authors, © 2014-2016 SourceLair Private Company, © 2012-2013 Christopher Jeffrey | `assets/term/xterm.js`, `xterm.css` | `xterm.LICENSE.txt` |
| `@xterm/addon-fit` | 0.10.0 | MIT | © 2019 The xterm.js authors | `assets/term/addon-fit.js` | `xterm-addon-fit.LICENSE.txt` |
| `@xterm/addon-unicode11` | 0.8.0 | MIT | © 2019 The xterm.js authors | `assets/term/addon-unicode11.js` | `xterm-addon-unicode11.LICENSE.txt` |
| `@xterm/addon-web-links` | 0.11.0 | MIT | © 2017 The xterm.js authors | `assets/term/addon-web-links.js` | `xterm-addon-web-links.LICENSE.txt` |

**D2Coding(OFL) 조건 요약**: 소프트웨어와 함께 번들·재배포 가능, 라이선스 전문을 함께 배포해야 함, **폰트 단독 판매 금지**, 폰트를 수정하면 "D2Coding" 이름을 쓸 수 없음 → 원본 파일을 수정 없이 사용 중.

## 2. Go 코어(`core.aar`)에 정적 링크되는 것

주요 구성요소:

| 구성요소 | 라이선스 | 저작권 | 라이선스 파일 |
|---|---|---|---|
| [Tailscale](https://github.com/tailscale/tailscale) (`tailscale.com`, tsnet) | BSD-3-Clause | © 2020 Tailscale Inc & contributors | `tailscale.LICENSE.txt` |
| wireguard-go (Tailscale fork) | MIT | © WireGuard LLC | `wireguard-go.LICENSE.txt` |
| gVisor (netstack, Tailscale 경유) | Apache-2.0 | © The gVisor Authors | `go/gvisor.dev/...` |
| Go 표준 라이브러리·런타임 | BSD-3-Clause | © 2009 The Go Authors | `go.LICENSE.txt` |
| `golang.org/x/crypto` (SSH) | BSD-3-Clause | © The Go Authors | `golang.org-x-crypto.LICENSE.txt` |
| `golang.org/x/mobile` (gomobile 바인딩) | BSD-3-Clause | © The Go Authors | `golang.org-x-mobile.LICENSE.txt` |
| [`github.com/pkg/sftp`](https://github.com/pkg/sftp) (이미지 업로드) | BSD-2-Clause | © 2013 Dave Cheney | `pkg-sftp.LICENSE.txt` |

**전체 목록 (간접 의존성 포함 36개)**: [`go-modules.csv`](app/src/main/assets/licenses/go-modules.csv), 전문은 [`licenses/go/`](app/src/main/assets/licenses/go/).
요약: BSD-3-Clause 20 · MIT 8 · Apache-2.0 6 · BSD-2-Clause 2 — [`go-licenses`](https://github.com/google/go-licenses)로 생성, restricted/forbidden/unknown 없음.

## 3. Android 라이브러리 (Gradle 의존성)

모두 **Apache License 2.0** (`Apache-2.0.txt`):

| 구성요소 | 저작권 |
|---|---|
| AndroidX Core, Activity, Fragment, Lifecycle, Biometric | © The Android Open Source Project |
| Jetpack Compose (UI, Foundation, Material 3) | © The Android Open Source Project |
| Kotlin 표준 라이브러리 | © JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines | © JetBrains s.r.o. and contributors |

테스트 전용(APK 미포함): JUnit 4 (EPL-1.0).

## 4. 상표

- "Tailscale"은 Tailscale Inc.의 상표입니다. PanoTerm은 Tailscale Inc.와 무관하며 보증받지 않았습니다. 앱 이름·아이콘에 Tailscale 로고를 쓰지 않습니다.
- "WireGuard"는 Jason A. Donenfeld의 등록 상표입니다.
- "Claude"는 Anthropic의 상표입니다. 설명 목적으로만 언급합니다.
- "D2Coding"은 NAVER Corporation의 예약 폰트 이름입니다.

## 5. 의존성 추가 시 규칙

새 라이브러리·폰트·에셋을 넣으면 **같은 커밋에서** 다음 세 곳을 함께 갱신합니다.

1. 이 문서
2. `app/src/main/assets/licenses/` (전문 추가 — `scripts/fetch-assets.ps1`에 URL 추가)
3. `app/src/main/java/dev/panoterm/ui/LicensesScreen.kt`

Go 의존성이 바뀌면 `scripts/gen-go-licenses.ps1` 실행.
