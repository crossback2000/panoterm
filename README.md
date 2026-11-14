# PanoTerm (1단계 MVP)

Tailscale 내장 + Claude Code 사용에 맞춘 개인용 안드로이드 SSH 클라이언트. 설계는 [DESIGN.md](DESIGN.md).

## 구조

```
core/                 Go 코어 → gomobile로 app/libs/core.aar 생성
  node.go             tsnet (Tailscale 내장, userspace 모드)
  ssh.go              SSH 세션 (x/crypto/ssh), keepalive
  keys.go             Ed25519 생성 / OpenSSH·PEM 키 가져오기
  netenv.go           안드로이드용 인터페이스·DNS 정보 주입
app/                  Kotlin + Jetpack Compose
  data/               호스트, 키(Keystore 암호화), known_hosts
  net/                TailscaleManager
  term/               KeyEncoder(K380), TermSession, xterm.js WebView, 입력 뷰
  ui/                 호스트 목록/편집, 키 관리, 터미널, 키 줄
  assets/term/        xterm.js 렌더러 (index.html, term.js) + fonts/D2Coding
  assets/licenses/    서드파티 라이선스 전문 (APK에 포함, 앱 ⓘ 화면에서 표시)
scripts/
  fetch-assets.ps1    xterm.js · D2Coding · 라이선스 전문 다운로드 (이미 커밋되어 있음, 버전 올릴 때만)
  build-core.ps1      Go 코어 → core.aar
  gen-go-licenses.ps1 Go 의존성 전체 라이선스 목록 생성 + 금지 라이선스 검사
THIRD_PARTY_NOTICES.md  사용한 오픈소스·폰트·상표 정리 및 공개 전 체크리스트
```

## 글꼴

터미널은 **D2Coding 1.3.2**(NAVER, SIL OFL 1.1)를 내장해 사용합니다. 한글이 정확히 영문 2칸 폭이라 Claude가 그리는 한글 섞인 표가 어긋나지 않습니다. 리거처 없는 Regular/Bold만 포함합니다 (약 8.5 MB).

## 개발 환경 준비 (Windows)

1. **Android Studio** 설치 → SDK Manager에서
   - Android SDK Platform 35
   - SDK Tools: **NDK (Side by side)**, Android SDK Command-line Tools
2. **Go 1.25+** 설치 (`go version` 확인)
3. 빌드:
   ```powershell
   .\scripts\build-core.ps1
   .\scripts\gen-go-licenses.ps1
   ```
4. Android Studio에서 `android-ssh` 폴더 열기 → Gradle 동기화 → S25에서 실행
   (휴대폰: 개발자 옵션 → USB 디버깅 또는 무선 디버깅)

## 처음 사용

1. 앱 실행 → Tailscale 카드의 **로그인** → 브라우저에서 tailnet 승인
2. **SSH 키** → Ed25519 생성 → **공개키 복사** → 서버 `~/.ssh/authorized_keys`에 추가
3. 호스트 추가 (또는 Tailnet 기기 목록에서 `+ 추가`) → 키 선택 → 저장 → 탭하여 접속
4. 처음 접속 시 호스트 키 지문 확인

## 서버 권장 설정

`~/.tmux.conf`:
```
set -g mouse on
set -g extended-keys on
set -g set-clipboard on
set -g history-limit 50000
set -ga update-environment MOBILE_TERM
```

`~/.claude/CLAUDE.md`에 추가 (폰에서 표가 넓게 나오는 문제 완화):
```
- If the environment variable MOBILE_TERM is set, I'm on a phone: avoid wide tables, prefer short bullet lists and keep lines under ~60 characters.
```

- 앱은 접속 명령으로 `export MOBILE_TERM=1; tmux -u new -A -s <세션>`을 실행합니다 (POSIX 셸 기준; fish 등은 기본 셸로 sh 계열 필요).
- 서버가 Windows면 tmux는 WSL 안에서 사용하세요.

## K380 / 키 입력

| 입력 | 동작 |
|---|---|
| Shift+Tab | `ESC [ Z` (Claude 모드 전환) |
| Shift+Enter / Alt+Enter | `ESC CR` (Claude 입력창 줄바꿈) |
| Ctrl+Backspace | `^W` (단어 삭제) |
| Ctrl+Shift+V / C | 붙여넣기 / 선택 복사 |
| Ctrl+Alt+= / − | 글자 크기 키우기 / 줄이기 |
| Ctrl+Alt+T | tmux 키 줄 표시 전환 |
| Fn+Esc | (K380 자체) Fn 잠금 → F1–F12 를 기본 키로 |

화면 키 줄: `Esc` `⇧Tab` `Esc²` `^C` `Tab` `Ctrl`(고정) `Alt`(고정) `← ↓ ↑ →` `/ @ | ~ -` `⌫` `⇧⏎ 줄바꿈` + 오른쪽 고정 **`⏎ Enter`**.
tmux 줄: `^B` `새 창` `다음` `이전` `확대` `스크롤` `분리` `PgUp` `PgDn` `q` (기본 prefix Ctrl+B 기준).

## 1단계에서 아직 안 된 것 (다음 단계)

- 백그라운드 연결 유지(Foreground Service), 네트워크 변경 시 자동 재연결 (지금은 "다시 연결" 버튼 → tmux 재부착)
- 가상 넓은 화면, 시각 전용 핀치 줌, 표 렌즈, 읽기 모드
- 하드웨어 키보드 한글 입력

## 라이선스

서드파티 구성요소는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 참고. 프로젝트 자체 라이선스는 공개 시 결정 예정.
- 생체인증 잠금, 키 재배치 UI, 여러 세션 탭 전환
