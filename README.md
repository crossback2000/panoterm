# PanoTerm

**Claude Code를 폰에서 편하게 쓰기 위한 안드로이드 SSH 터미널.**
Tailscale 내장 · 넓은 표를 크게 보는 "표 렌즈" · K380 같은 블루투스 키보드 완벽 지원 · 광고/결제/계정/클라우드 없음.

> Pano(rama) + Term — 넓은 내용을 좌우로 훑어보는 터미널. 설계 배경은 [DESIGN.md](DESIGN.md).

## 주요 기능

### 넓은 표·작은 글씨 문제 해결
- **표 렌즈 (▦)** — 화면 기록에서 Claude가 그린 표(박스 문자 / 마크다운)를 찾아 **큰 글씨의 네이티브 표**로 보여줌. 첫 열 고정 + 좌우 스크롤, 줄바꿈으로 깨진 표도 복원, 마크다운으로 복사. 표가 보이면 화면 우상단에 `▦ 표 N개 크게 보기` 칩이 뜸.
- **넓게 보기 (↔)** — 서버에는 120/160/200열로 알리고 폰은 읽기 좋은 글씨 크기로 그린 뒤 **손가락으로 좌우 이동**. 표가 줄바꿈 없이 원래 모양대로 나옴.
- **읽기 모드 (📖)** — Claude의 마지막 응답을 큰 글씨로 다시 흘려 보여줌. 표는 행마다 `▸ 항목: 값` 카드로 변환.
- **핀치 줌** — 글자 크기만 바꿈 (넓게 모드에서는 열 수 유지 → Claude 화면이 다시 그려지지 않음).
- **D2Coding 내장** — 한글이 정확히 2칸이라 한글 섞인 표가 어긋나지 않음.

### Claude Code 작업 흐름
- **승인 버튼** — "Do you want to proceed?" 같은 선택지를 감지해 큰 버튼(1/2/3/Esc)으로 표시.
- **알림** — 다른 앱을 보고 있을 때 터미널 벨 / OSC 9 / OSC 777 알림을 안드로이드 알림으로 (설정은 아래).
- **입력창 (✎)** — 여러 줄 프롬프트를 삼성 키보드·음성 입력으로 편하게 작성 → `보내기 ⏎` / `넣기만`, 호스트별 기록.
- **이미지 (🖼)** — 갤러리 이미지를 SFTP로 `~/.cache/panoterm/`에 올리고 경로를 프롬프트에 자동 입력.
- **포트 미리보기 (⇄)** — 서버의 개발 서버(예: 3000번)를 SSH 터널로 폰 브라우저에서 열기.
- **클립보드 연동** — 서버의 OSC 52(tmux `set-clipboard`)가 폰 클립보드로. URL은 탭해서 열기.

### 연결
- **Tailscale 내장** (tsnet, userspace) — 폰에 Tailscale 앱이 없어도 tailnet 접속. VPN 슬롯을 쓰지 않아 다른 VPN과 공존. tailnet 기기 목록에서 바로 호스트 추가.
- **SSH 키 전용** — Ed25519 생성 / OpenSSH·PEM 가져오기(암호 걸린 키 포함). 개인키는 Android Keystore(StrongBox) AES-GCM으로 암호화. 호스트 키 TOFU 확인.
- **tmux 자동 연결 + 자동 재연결** — 끊기면 백오프 재시도, 네트워크 복구 시 즉시 재접속, 같은 tmux 세션에 재부착. 백그라운드 유지 서비스.
- **홈 화면 바로가기** — 호스트를 홈 화면에 고정해 한 번에 접속. 생체인증 잠금(선택).

### 키보드 (K380 등)

| 입력 | 동작 |
|---|---|
| Shift+Tab | `ESC [ Z` (Claude 모드 전환) |
| Shift+Enter / Alt+Enter | `ESC CR` (Claude 입력창 줄바꿈) |
| Ctrl+Backspace | `^W` (단어 삭제) |
| Shift+Space (설정 가능) | 한/영 전환 — 앱 내장 두벌식 오토마타 |
| Ctrl+Shift+V / C | 붙여넣기 / 선택 복사 |
| Ctrl+Shift+F / R / E / W | 표 렌즈 / 읽기 모드 / 입력창 / 넓게 보기 전환 |
| Ctrl+Alt+= / − | 글자 크기 |
| Ctrl+Alt+T | tmux 키 줄 표시 |
| Ctrl+Tab / Ctrl+Shift+Tab | 다음 / 이전 세션 |
| CapsLock (설정) | Ctrl로 사용 |
| Fn+Esc | (K380 자체) Fn 잠금 → F1–F12를 기본 키로 |

화면 키 줄: `Esc` `⇧Tab` `Esc²` `^C` `Tab` `Ctrl`(고정) `Alt`(고정) `← ↓ ↑ →`(길게 누르면 반복) `/ @ | ~ -` `⌫` `⇧⏎ 줄바꿈` + 오른쪽 고정 **`⏎ Enter`**.
tmux 줄: `^B` `새 창` `다음` `이전` `확대` `스크롤` `분리` `PgUp` `PgDn` `q`.

## 설치

1. [Releases](../../releases)에서 `PanoTerm-1.0.0.apk`를 받아 설치 (출처를 알 수 없는 앱 허용).
2. 앱 실행 → **Tailscale 로그인** → 브라우저에서 승인.
3. **SSH 키** → Ed25519 생성 → **공개키 복사** → 서버 `~/.ssh/authorized_keys`에 추가.
4. Tailnet 기기 목록에서 `+ 추가` (또는 `+`) → 사용자·키 선택 → 저장 → 탭하여 접속 → 호스트 키 지문 확인.

## 서버 권장 설정

`~/.tmux.conf`
```
set -g mouse on
set -g extended-keys on          # Shift+Enter 등 확장 키 전달
set -g set-clipboard on          # 서버에서 복사 → 폰 클립보드 (OSC 52)
set -g allow-passthrough on      # 알림(OSC 777) 통과
set -g history-limit 50000
set -ga update-environment MOBILE_TERM
```

`~/.claude/CLAUDE.md` — 폰에서 표 대신 목록으로 답하게:
```
- If the environment variable MOBILE_TERM is set, I'm on a phone: avoid wide tables,
  prefer short bullet lists and keep lines under ~60 characters.
```

**Claude 알림** — [`extras/claude-notify.sh`](extras/claude-notify.sh)를 `~/.claude/`에 복사하고 `chmod +x` 후, 파일 안의 예시대로 `~/.claude/settings.json`의 `Notification` / `Stop` hook에 등록. (또는 Claude Code의 알림 채널을 터미널 벨로 설정해도 PanoTerm이 알림으로 바꿔 줍니다.)

서버가 Windows라면 tmux는 WSL 안에서 사용하세요.

## 빌드

필요: JDK 17, Android SDK(platform 35, build-tools 35, NDK), Go 1.25+.

```powershell
.\scripts\build-core.ps1        # Go 테스트 → core.aar (arm64 + 에뮬레이터용 amd64)
.\gradlew assembleDebug         # 디버그 (에뮬레이터 x86_64 포함)
.\gradlew assembleRelease       # 릴리스 (arm64 전용)
```

릴리스 서명: 저장소 밖의 `~/.panoterm/keystore.properties`(또는 `$PANOTERM_KEYSTORE_PROPS`)를 읽습니다.
```
storeFile=C:/Users/<you>/.panoterm/release.jks
storePassword=...
keyAlias=panoterm
keyPassword=...
```

에셋·라이선스: `scripts/fetch-assets.ps1`(xterm.js · D2Coding · 라이선스 전문), `scripts/gen-go-licenses.ps1`(Go 의존성 라이선스 목록 + 금지 라이선스 검사). 결과물은 저장소에 커밋되어 있어 평소엔 실행할 필요 없음.

## 테스트

```powershell
cd core; go test ./...           # 실제 SSH 서버(PTY·SFTP·포워딩)를 띄워 Go 코어 통합 테스트
.\gradlew testDebugUnitTest      # 한글 오토마타 · 출력 스캐너(벨/OSC) · 링 버퍼
```

## 구조

```
core/                  Go 코어 (gomobile → app/libs/core.aar)
  node.go              Tailscale 내장 (tsnet)
  ssh.go, extras.go    SSH 세션, keepalive, SFTP 업로드, 로컬 포트 포워딩
  keys.go              Ed25519 생성 / 키 가져오기
  netenv.go            안드로이드 네트워크 인터페이스·DNS·기본 경로 주입
app/src/main/
  assets/term/         xterm.js 렌더러 (term.js: 넓게 보기, 핀치, 표 추출, 승인 감지) + D2Coding
  assets/licenses/     서드파티 라이선스 전문
  java/dev/panoterm/
    data/              호스트 · 키(Keystore 암호화) · known_hosts · 설정
    net/               TailscaleManager
    term/              세션 · 키 인코더 · 한글 오토마타 · 출력 스캐너 · 백그라운드 서비스
    ui/                화면 (호스트, 키, 터미널, 표 렌즈, 읽기, 설정, 라이선스)
extras/                서버 쪽 도우미 (Claude 알림 hook)
```

## 라이선스

PanoTerm: [MIT](LICENSE). 서드파티 구성요소는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 참고.

"Tailscale"은 Tailscale Inc.의, "Claude"는 Anthropic의 상표이며 이 프로젝트는 이들과 무관합니다.
