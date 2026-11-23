# PanoTerm

**한국어** | [English](README.en.md)

PanoTerm은 Claude Code 같은 AI 코딩 에이전트를 모바일에서 운영하기 위한 Android용 SSH 클라이언트입니다. Tailscale 네트워크를 앱에 내장해 별도 VPN 없이 사내 tailnet의 서버에 접속하고, 넓은 터미널 출력(표, 긴 로그)을 휴대폰 화면에서 읽을 수 있도록 설계되었습니다.

- **라이선스**: MIT (오픈 소스)
- **지원 플랫폼**: Android 12 (API 31) 이상, arm64
- **UI 언어**: 한국어, 영어 (기기 설정 또는 앱 설정에서 선택)
- **배포**: [GitHub Releases](../../releases) — 서명된 APK, 앱 내 자동 업데이트

광고, 결제, 계정, 클라우드 동기화, 사용 통계 수집이 없습니다. 모든 데이터는 기기에만 저장됩니다.

---

## 목차

1. [주요 기능](#주요-기능)
2. [아키텍처](#아키텍처)
3. [보안](#보안)
4. [설치 및 초기 설정](#설치-및-초기-설정)
5. [서버 구성 권장 사항](#서버-구성-권장-사항)
6. [키보드 및 단축키](#키보드-및-단축키)
7. [업데이트 정책](#업데이트-정책)
8. [소스에서 빌드](#소스에서-빌드)
9. [CI/CD 및 릴리스 절차](#cicd-및-릴리스-절차)
10. [프로젝트 구조](#프로젝트-구조)
11. [알려진 제약 사항](#알려진-제약-사항)
12. [라이선스 및 상표](#라이선스-및-상표)

---

## 주요 기능

### 넓은 출력 가독성

| 기능 | 설명 |
|---|---|
| **넓게 보기** (`↔`) | 서버에는 120·160·200열 터미널로 알리고, 화면에는 읽기 좋은 글자 크기로 그린 뒤 가로로 밀어 봅니다. 표와 정렬된 출력이 줄바꿈으로 깨지지 않습니다. |
| **표 크게 보기** (`▦`) | 화면과 스크롤백에서 상자 문자 표(얇은 선, 굵은 선, 이중 선)와 마크다운 표를 찾아 네이티브 표로 다시 보여줍니다. 첫 열 고정, 가로 스크롤, 마크다운 복사를 지원합니다. |
| **읽기 모드** (`📖`) | 에이전트의 마지막 응답을 큰 글자로 보여주고, 표는 행마다 `▸ 항목: 값` 목록으로 풀어 줍니다. |
| **D2Coding 글꼴 내장** | 한글이 정확히 영문 두 칸 폭이라 한글이 섞인 표도 열이 어긋나지 않습니다. |
| **GPU 렌더링** | 기본은 WebGL로 그리고, 지원하지 않는 기기에서는 DOM으로 자동 전환합니다. 픽셀 단위 관성 스크롤을 지원합니다. |

### AI 에이전트 작업 흐름

- **승인 버튼**: "Do you want to proceed?" 같은 선택지가 나오면 큰 버튼(1 / 2 / 3 / Esc)을 띄웁니다.
- **작업 상태 탭**: 세션과 tmux 창마다 작업 중(파랑), 입력·승인 대기(주황), 쉬는 중(초록)을 보여주고, 탭하면 바로 전환합니다.
- **알림**: 다른 앱을 보는 동안 에이전트가 입력을 기다리면 Android 알림을 보냅니다(Claude Code hook 또는 터미널 벨).
- **자동완성**: Claude 입력창의 `/` 명령, 사용자 명령(`~/.claude/commands`), 스킬, `@` 파일, 셸의 `cd` 경로를 추천합니다. 서버 조회는 별도 SSH 채널로 처리해서 터미널 입력에 섞이지 않습니다.
- **명령 팔레트** (`⚡`): `/compact`, `/clear`, `claude --continue` 같은 명령을 바로 보냅니다. 목록은 직접 편집할 수 있습니다.
- **파일 탐색기** (`📁`): 터미널이 있는 폴더에서 SFTP 탐색기가 열립니다. 구문 강조 뷰어와 간단한 편집기를 제공하며, 서버에서 파일이 바뀌면 덮어쓰기 전에 확인합니다. 저장은 원자적 교체로 처리하고, `.env` 값은 가린 채로 엽니다.
- **이미지 전달**: 사진이나 스크린샷을 서버에 올리고 그 경로를 프롬프트에 넣습니다.
- **포트 포워딩 미리보기**: 서버의 개발 서버(예: `localhost:3000`)를 휴대폰 브라우저로 엽니다. 30분 동안 쓰지 않으면 자동으로 닫힙니다.

### 연결 및 세션 유지

- **Tailscale 내장** (tsnet, userspace): Android VPN 슬롯을 쓰지 않아 다른 VPN과 함께 쓸 수 있습니다. 로그인하면 tailnet 기기 목록에서 바로 호스트를 추가할 수 있습니다.
- **tmux 자동 연결**: 접속하면 지정한 tmux 세션에 붙습니다. 서버에 tmux가 없으면 패키지 관리자(apt, dnf, yum, pacman, zypper, apk, brew, pkg)를 찾아 설치까지 도와줍니다.
- **연결 유지와 자동 재연결**: 15초마다 연결을 확인하고, 연속 3번 응답이 없을 때만 끊긴 것으로 판단합니다(OpenSSH `ServerAliveCountMax`와 같은 방식). 네트워크가 바뀌거나 끊기면 지수 백오프로 다시 연결합니다.
- **백그라운드 유지**: 세션이 열려 있는 동안만 포그라운드 서비스와 wake lock을 유지합니다.
- **홈 화면 바로가기**: 자주 쓰는 호스트를 홈 화면에 둘 수 있습니다.

### 입력

- 휴대폰 키보드가 일반 입력칸과 똑같이 글자를 조합하고, 확정된 글자만 서버로 보냅니다. 삼성 키보드, Gboard, 천지인, 일본어 입력이 같은 규칙으로 동작합니다.
- 블루투스 하드웨어 키보드(Logitech K380 기준)의 단축키, CapsLock→Ctrl, 볼륨 키→Ctrl을 지원합니다.
- 화면 키 줄은 순서와 구성을 바꿀 수 있고, 편집 화면에서 키마다 설명을 보여줍니다.

---

## 아키텍처

```
┌──────────────────────────────────────────────────────────────┐
│ Android 앱 (Kotlin, Jetpack Compose)                         │
│  ├─ UI · 키 입력(ImeMirror) · 알림 · 포그라운드 서비스         │
│  ├─ 터미널 렌더링: xterm.js (WebView, WebGL/DOM)               │
│  └─ 키 저장소: Android Keystore (StrongBox / TEE)             │
│                    ↕ JNI (gomobile bind)                      │
│ Go 코어 (core.aar)                                            │
│  ├─ tsnet           Tailscale 내장 (userspace 네트워킹)        │
│  ├─ x/crypto/ssh    SSH 세션, PTY, keepalive, 포트 포워딩      │
│  └─ pkg/sftp        파일 탐색기, 업로드, 원자적 저장            │
└──────────────────────────────────────────────────────────────┘
```

자세한 설계는 [DESIGN.md](DESIGN.md)를 참고하세요.

---

## 보안

| 영역 | 구현 |
|---|---|
| 인증 | SSH 공개키 인증만 지원합니다(비밀번호 인증 없음). |
| 개인키 보관 | Ed25519 키는 Android Keystore의 AES-GCM 키로 암호화해 앱 저장소에 둡니다. |
| 하드웨어 키 | 선택 시 StrongBox(없으면 TEE)에서 EC P-256 키를 만들며, 키가 칩 밖으로 나오지 않습니다. "쓸 때마다 지문 확인"을 켜면 생체 인증 없이는 서명하지 않습니다. |
| 서버 확인 | 처음 접속할 때 호스트 키 지문을 확인하고(TOFU), 키가 바뀌면 경고합니다. |
| 앱 잠금 | 앱을 열 때와 새 세션을 열 때 생체 인증을 요구할 수 있습니다. |
| 백업 | 내보낸 파일은 PBKDF2-HMAC-SHA256(600,000회) + AES-256-GCM으로 암호화합니다. 하드웨어 키는 포함되지 않습니다. |
| 업데이트 | 받은 APK의 서명 인증서가 설치된 앱과 같고 SHA-256 해시가 릴리스 정보와 일치할 때만 설치합니다. |
| 클립보드 | 서버→휴대폰 클립보드 복사(OSC 52)는 기본으로 꺼져 있습니다. OSC 8 링크는 실제 주소를 보여주고 확인을 받습니다. |
| 데이터 수집 | 사용 통계와 원격 전송이 없습니다. 업데이트 확인(GitHub API)만 하며, 설정에서 끌 수 있습니다. |

보안 취약점은 공개 이슈 대신 저장소의 **Security 탭 → [Report a vulnerability](../../security/advisories/new)** 로 비공개 제보해 주세요. 제보 내용은 관리자에게만 보입니다.

---

## 설치 및 초기 설정

1. [Releases](../../releases)에서 최신 `PanoTerm-x.y.z.apk`를 받아 설치합니다. "출처를 알 수 없는 앱" 허용이 필요합니다.
2. 앱에서 **Tailscale 로그인**을 눌러 브라우저에서 승인합니다.
3. **SSH 키**에서 키를 만들고, 공개키를 서버의 `~/.ssh/authorized_keys`에 추가합니다.
4. tailnet 기기 목록에서 **+ 추가**를 누르고 사용자와 키를 지정해 저장합니다.
5. 처음 접속할 때 서버 호스트 키 지문을 확인합니다.

**언어 설정**: 기기 언어가 한국어면 한국어, 그 밖에는 영어로 표시됩니다. Android 13 이상에서는 **설정 → 언어** 또는 기기 설정의 앱별 언어에서 바꿀 수 있습니다.

**기기 교체**: 설정 → 백업 → 내보내기로 호스트, 키, 서버 지문, 명령 목록을 암호화된 파일 하나로 저장하고, 새 기기에서 가져오기 하면 됩니다.

---

## 서버 구성 권장 사항

### tmux

`~/.tmux.conf`:

```
set -g mouse on
set -g extended-keys on          # Shift+Enter 같은 확장 키 전달
set -g set-clipboard on          # 서버에서 복사한 내용을 휴대폰 클립보드로 (앱 설정도 켜야 함)
set -g allow-passthrough on      # 알림 시퀀스가 tmux를 통과하도록
set -g history-limit 50000
set -ga update-environment MOBILE_TERM
```

tmux는 표준 경로 외에 Homebrew(`/opt/homebrew/bin`), linuxbrew, `~/.local/bin`, nix 경로에서도 찾습니다. 찾지 못하면 일반 셸로 접속하고, 화면 위 경고의 **tmux 설치** 버튼으로 설치할 수 있습니다. Windows 서버는 WSL 안에서 tmux를 실행하세요.

### Claude Code

앱으로 접속하면 `MOBILE_TERM=1` 환경 변수가 설정됩니다. `~/.claude/CLAUDE.md`에 아래 내용을 넣으면 모바일에서는 좁은 형식으로 답하게 할 수 있습니다.

```
- If the environment variable MOBILE_TERM is set, I'm on a phone: avoid wide tables,
  prefer short bullet lists and keep lines under ~60 characters.
```

### 알림

[`extras/claude-notify.sh`](extras/claude-notify.sh)를 `~/.claude/`에 복사하고(`chmod +x`), 파일 안의 안내대로 `~/.claude/settings.json`의 hook에 등록합니다.

---

## 키보드 및 단축키

Logitech K380 기준입니다. K380은 Fn+Esc로 Fn 잠금을 걸어 두면 F1–F12를 바로 쓸 수 있습니다.

| 키 | 동작 |
|---|---|
| Shift+Tab | Claude Code 모드 전환 (`ESC [ Z`) |
| Shift+Enter, Alt+Enter | 보내지 않고 줄바꿈 |
| Ctrl+Backspace | 단어 지우기 |
| Shift+Space | 한/영 전환 (휴대폰 키보드 설정을 따름) |
| Ctrl+Shift+V / C | 붙여넣기 / 복사 |
| Ctrl+Shift+F | 표 크게 보기 |
| Ctrl+Shift+R | 읽기 모드 |
| Ctrl+Shift+W | 넓게 보기 전환 |
| Ctrl+Shift+P | 명령 팔레트 |
| Ctrl+Shift+E | 파일 탐색기 (편집 중 Ctrl+S로 저장) |
| Ctrl+Alt+= / − | 글자 크기 |
| Ctrl+Alt+T | tmux 키 줄 보이기/숨기기 |
| Ctrl+Tab | 다음 세션 |
| CapsLock | Ctrl로 사용 (설정에서 켜기) |

하드웨어 키보드가 없을 때는 화면 키 줄을 씁니다. 기본 구성은 Tab, Esc, 커서 패드(`◀ ▶`), 방향키, 명령 팔레트, `/`, ⌫, Enter입니다. 설정 → 화면 키 줄 편집에서 ⇧Tab, Esc 두 번, ^C, Ctrl/Alt, 기호 키 등을 추가하고 순서를 바꿀 수 있습니다.

---

## 업데이트 정책

- 앱을 열 때 GitHub 릴리스를 확인합니다(최대 6시간에 한 번).
- 자동 업데이트를 켜면 새 버전을 받아 설치합니다. 기본은 Wi-Fi에서만 받습니다.
- 열린 세션이 있으면 모두 닫힐 때까지 설치를 미룹니다(설치하면 앱이 다시 시작되기 때문).
- 처음 한 번은 "이 출처의 앱 설치 허용"이 필요합니다.

---

## 소스에서 빌드

### 요구 사항

| 도구 | 버전 |
|---|---|
| JDK | 17 |
| Android SDK | platform `android-37.2`, build-tools 37.0.0 |
| Android NDK | 30.0.16248370 (x86_64 호스트 필요) |
| Go | `core/go.mod` 기준 (현재 1.27) |

### 빌드

```bash
bash scripts/build-core.sh        # Go 코어 → app/libs/core.aar (Windows: scripts/build-core.ps1)
./gradlew assembleDebug           # 디버그 빌드
./gradlew assembleRelease         # 릴리스 빌드 (서명 정보 필요)
```

릴리스 서명 정보는 저장소 밖의 `~/.panoterm/keystore.properties`(또는 `PANOTERM_KEYSTORE_PROPS`가 가리키는 파일)에서 읽습니다.

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=panoterm
keyPassword=...
```

### 테스트

```bash
cd core && go test ./...          # 테스트용 SSH 서버로 접속, keepalive, SFTP, 포워딩 검증
./gradlew testDebugUnitTest       # 입력(IME), 표 인식, 출력 감지, 백업 암호화 등
node --test app/src/test/js/*.test.js   # 표 인식·읽기 모드 (JavaScript)
```

### 다국어 리소스

UI 문자열은 `app/src/main/res/values/strings.xml`(영어, 기본)과 `values-ko/strings.xml`(한국어)에 있습니다. 새 언어는 `values-<언어 코드>/strings.xml`을 추가하면 되고, Android 13+의 앱별 언어 목록은 빌드할 때 자동으로 생성됩니다.

### 서드파티 에셋

xterm.js, D2Coding, 라이선스 원문은 저장소에 포함되어 있습니다. 버전을 올릴 때는 `scripts/fetch-assets.ps1`, Go 의존성이 바뀌면 `scripts/gen-go-licenses.ps1`을 실행합니다.

---

## CI/CD 및 릴리스 절차

| 워크플로 | 트리거 | 내용 |
|---|---|---|
| `CI` | `main` push, 모든 PR | Go 테스트, JavaScript 테스트, Go 코어 빌드, 앱 단위 테스트, 디버그 빌드. Secret을 쓰지 않아 포크 PR에서도 안전합니다. |
| `Release` | `vX.Y.Z` 태그 | 서명된 APK 빌드, 서명 인증서 검증, SHA-256 계산, GitHub 릴리스 게시 |

릴리스 절차:

1. 기능 브랜치에서 PR을 만들고 CI 통과 후 `main`에 병합합니다.
2. `release-notes/vX.Y.Z.md`를 쓰고 `app/build.gradle.kts`의 버전을 올려 커밋합니다.
3. `git tag vX.Y.Z && git push origin vX.Y.Z` → `Release` 워크플로가 APK를 게시하고, 설치된 앱은 자동 업데이트로 받습니다.

서명 키는 저장소에 없고 암호화된 Actions Secrets(`PANOTERM_KEYSTORE_B64`, `PANOTERM_STORE_PASSWORD`, `PANOTERM_KEY_ALIAS`, `PANOTERM_KEY_PASSWORD`)에만 있습니다. 릴리스 워크플로는 APK 서명 인증서가 `EXPECTED_CERT_SHA256`과 다르면 실패합니다(서명이 바뀌면 기존 설치본이 업데이트를 받을 수 없기 때문). 포크해서 따로 배포하려면 자체 키를 Secrets에 등록하고 이 값을 바꾸세요.

---

## 프로젝트 구조

```
core/                    Go 코어 (gomobile → app/libs/core.aar)
  node.go                Tailscale (tsnet)
  ssh.go, extras.go      SSH 세션, keepalive, SFTP 업로드, 포트 포워딩
  files.go               파일 탐색기 (SFTP 목록·읽기·원자적 저장)
  keys.go, hwkey.go      키 생성·가져오기, 하드웨어 키 서명
  netenv*.go             Android 네트워크 정보 연동
app/src/main/
  java/dev/panoterm/     Android 앱 (Kotlin, Compose)
    term/                터미널 세션, 입력 처리, 자동완성, 출력 감지
    ui/                  화면 (호스트, 터미널, 파일 탐색기, 설정 등)
    data/                설정, 호스트, 키 저장소, 백업
    net/                 Tailscale 상태, 업데이트
  res/values*/           UI 문자열 (영어 기본, 한국어)
  assets/term/           xterm.js 화면, 표 인식, D2Coding
  assets/licenses/       오픈소스 라이선스 원문
extras/                  서버용 스크립트 (Claude 알림)
scripts/                 빌드·에셋·라이선스 스크립트
release-notes/           버전별 변경 사항
```

---

## 알려진 제약 사항

- Android 12 미만과 32비트 기기는 지원하지 않습니다. 릴리스 APK는 arm64만 포함합니다.
- Android NDK가 x86_64 호스트용으로만 배포되어 ARM Linux에서는 APK를 만들 수 없습니다(CI에서 빌드).
- Mosh는 지원하지 않습니다. tmux와 자동 재연결로 세션을 유지합니다.
- 앱별 언어 선택은 Android 13 이상에서만 지원합니다. Android 12에서는 기기 언어를 따릅니다.

---

## 라이선스 및 상표

PanoTerm 소스 코드는 [MIT 라이선스](LICENSE)로 배포됩니다. 포함된 서드파티 구성 요소와 라이선스는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 정리되어 있으며, 앱의 **설정 → 오픈소스 라이선스**에서도 볼 수 있습니다.

Tailscale은 Tailscale Inc., WireGuard는 Jason A. Donenfeld, Claude는 Anthropic의 상표입니다. PanoTerm은 이들 회사와 관계가 없으며 보증을 받지 않았습니다.
