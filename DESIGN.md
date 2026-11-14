# PanoTerm 설계 문서

> **구현 현황 (v1.0.0)**: 아래 1–4단계 모두 구현. 예외 — Mosh(선택, 미구현: tmux + 자동 재연결로 대체), 키 재배치는 CapsLock→Ctrl과 한/영 키 선택만 제공.
> 터미널 렌더러는 Termux 대신 xterm.js(MIT)를 사용 (§1 참고). 앱 이름은 PocketSSH → **PanoTerm** (동명 앱 존재로 변경).
>
> **검증**: Go 코어 통합 테스트(실제 SSH 서버: PTY·SFTP·포워딩·호스트키), Kotlin 단위 테스트(한글 오토마타·출력 스캐너·링 버퍼), Android 15 에뮬레이터에서 접속·렌더링·표 렌즈·읽기 모드·승인 버튼·넓게 보기·키 입력 확인. 실제 S25 Ultra + K380 + Tailscale 로그인은 사용자 기기에서 확인 필요.

> 목표: 광고·유료 기능·클라우드 없이, **Tailscale 내장 + Claude Code 사용에 최적화**된 개인용 안드로이드 SSH 클라이언트.
> 대상 기기: Galaxy S25 Ultra + Logitech K380 블루투스 키보드.

---

## 0. 참고한 기존 앱과 가져올 장점

| 앱 | 가져올 장점 | 피할 점 |
|---|---|---|
| Termius | 깔끔한 UI, 호스트/스니펫 관리, 키보드 상단 추가 키, SFTP | 유료 동기화, 계정 필수 |
| ConnectBot | 오픈소스, 무광고·무계정·무클라우드 | 투박한 UI, SFTP 부족 |
| JuiceSSH | 스니펫, 플러그인 개념 | 사실상 유지보수 모드, Pro 결제 |
| Termux | 커스텀 Extra Keys, 하드웨어 키보드 처리 | SSH 클라이언트가 아님 |
| Moshi / TermAI 등 | AI 에이전트(Claude Code) 작업 흐름 중심 설계 | 상용 |

---

## 1. 아키텍처

```
┌─ Kotlin + Jetpack Compose
│    UI, 터미널 뷰, 키 입력, 표 렌즈, 키 저장소(Keystore), 알림
│        ↕ JNI (gomobile bind)
└─ Go 코어 (.aar)
     ├─ tsnet          : Tailscale 내장 (userspace 모드, VpnService 불필요)
     ├─ x/crypto/ssh   : SSH 세션, PTY, 포트 포워딩, SFTP(pkg/sftp)
     └─ 재연결 관리자   : 네트워크 변경 감지 → 재접속 → tmux 재부착
```

- 터미널 렌더링: **xterm.js(MIT) in WebView** — 렌더링만 담당하고 키 입력은 Kotlin이 직접 처리.
  (Termux 라이브러리는 `TerminalSession`이 로컬 프로세스 전용 final 클래스라 SSH에 붙이기 어렵고 GPLv3라서 제외.
  xterm.js는 고정 열 수·확대·가로 스크롤·Unicode 11 폭 처리가 쉬워 ②번 기능에 유리)
- 저장소: JSON 파일(호스트·키 메타·known_hosts) + Keystore(StrongBox) AES-GCM으로 개인키 암호화
- 최소 SDK: Android 12 (API 31) 이상 권장

---

## 2. 요구사항별 설계

### ① Tailscale 내장
- `tsnet`/`libtailscale`을 userspace 모드로 사용 → 다른 VPN·공식 Tailscale 앱과 공존.
- 최초 1회 로그인 URL을 브라우저로 열기 또는 auth key 입력. 상태는 앱 내부 저장소에 보관.
- 호스트 목록에 tailnet 피어(MagicDNS 이름, 온라인 상태) 자동 표시 → 탭 한 번 접속.
- 서버 쪽 `tailscaled`는 그대로 사용.

### ② 넓은 표 / 작은 글씨 문제 (핵심 기능)
열 수(PTY cols)와 글자 크기를 **분리**한다.

1. **가상 넓은 화면 (Wide Viewport)**: 원격에는 고정 열 수(예: 140)를 알리고, 화면은 큰 글씨로 렌더링 + 좌우 팬. 커서 자동 추적.
2. **핀치 줌 = 시각 확대만**: PTY 크기를 바꾸지 않아 Claude 화면 재그리기 없음. (별도 버튼으로 "현재 크기에 맞춰 리사이즈")
3. **표 렌즈**: 화면/스크롤백에서 박스 문자 표(`┌─┬─┐│`)·마크다운 표(`| a | b |`) 감지 → 📊 버튼 → 네이티브 표 뷰(첫 열 고정, 좌우 스크롤, 셀 줄바꿈, 가로모드).
4. **읽기 모드**: Claude의 마지막 응답을 ANSI 제거 후 화면 폭에 맞게 리플로우하여 큰 글씨로 표시.
5. **원격 보조**: 접속 시 `MOBILE_TERM=1` 전송(서버 `AcceptEnv` 필요, 또는 접속 명령에 `export`). `~/.claude/CLAUDE.md`에 "MOBILE_TERM이 있으면 넓은 표 대신 목록으로 답할 것" 추가.
6. **호스트 프로필**: "Claude 모드"(열 수·글꼴·방향), "DeX/외부 모니터 모드".
7. **한글 폭 처리**: ✅ D2Coding 1.3.2 내장(1단계 완료, OFL 1.1), CJK 2칸 폭(wcwidth)·박스 문자 폭을 정확히 처리해 한글이 섞인 표가 어긋나지 않게 함.

### ③ 클라우드 없음
- 모든 데이터 로컬 암호화 저장. 백업은 암호화된 파일 내보내기/가져오기만.

### ④ SSH 키 필수
- Ed25519 생성/가져오기(OpenSSH 형식, 암호 걸린 키 포함). Keystore AES로 래핑, 사용 시 생체인증.
- 옵션: StrongBox 하드웨어 ECDSA P-256 키(기기 밖 유출 불가).
- 공개키 복사 / 공유 / QR 내보내기.
- 비밀번호 인증은 기본 비활성.
- 호스트 키 검증(TOFU, known_hosts 관리, 키 변경 시 경고).

### ⑤ K380 블루투스 키보드
- 하드웨어 키는 IME를 거치지 않고 `dispatchKeyEvent`로 직접 처리.
- 정확한 시퀀스: Shift+Tab → `ESC[Z`, Alt → Meta(ESC 접두), F1–F12, Home/End/PgUp/PgDn, Shift+Enter → 줄바꿈 시퀀스.
- 안내: K380은 **Fn+Esc로 Fn 잠금**(F키를 기본으로).
- 키 재배치: Caps→Ctrl, Win/Cmd→Alt 등. 삼성 OS가 가로채는 키는 재배치 안내.
- 앱 단축키: `Ctrl+Shift+T` 새 세션, `Ctrl+Tab` 세션 전환, `Ctrl+Shift+V` 붙여넣기, `Ctrl+Alt+= / -` 확대·축소 (Ctrl+Shift+- 는 Claude Code의 Ctrl+_ 실행취소와 겹쳐서 제외), `Ctrl+Shift+F` 표 렌즈, `Ctrl+Shift+R` 읽기 모드.
- 한글: 하드웨어 키보드 입력 시에도 한/영 전환 키(설정 가능, 예: Shift+Space / 오른쪽 Alt) + 앱 내 한글 오토마타.

### ⑥ 입력하기 어려운 키
- **Claude 키 줄** (순서 편집 가능):
  `Esc` · `⇧Tab` · `Esc Esc` · `^C` · `Tab` · **`⏎ Enter`** · **`⇧⏎ 줄바꿈`** · `↑↓←→` · `/` · `@` · `|` · `~`
  - `⏎ Enter`: `\r` 전송 (프롬프트 전송, 선택지 확인). 키 줄 오른쪽 끝에 크게 배치.
  - `⇧⏎ 줄바꿈`: `ESC \r`(Alt+Enter) 전송 → Claude Code 입력창에서 줄바꿈.
- **tmux 키 줄** (전환 가능): `^B`, 창 전환(`^B n`/`^B p`), 줌(`^B z`), 스크롤 모드(`^B [`).
- 고정 수정키: Ctrl/Alt 한 번 탭 = 다음 한 글자, 두 번 = 잠금.
- 제스처: 키 줄 좌우 스와이프 = 방향키 반복, 두 손가락 왼쪽 스와이프 = Esc, (옵션) 볼륨 아래 = Ctrl.
- **입력창(작성 박스)**: 여러 줄 입력, 삼성 키보드 한글·음성 입력 사용 후 한 번에 전송. 호스트별 프롬프트 기록.
- **명령 팔레트**: `/compact`, `/clear`, 스니펫, 사용자 정의 명령.

---

## 3. tmux 연동
- Tailscale은 네트워크 통로, tmux는 서버에서 실행 → 일반 SSH와 동일하게 동작.
- 호스트 설정 "접속 시 tmux 자동 부착" + 세션 이름: 접속 명령 `tmux new -A -s main`.
- 자동 재연결: 네트워크 변경/백그라운드 복귀 시 재접속 → 같은 세션 재부착. Claude는 계속 실행 중.
- 서버 권장 `~/.tmux.conf`:
  ```
  set -g mouse on            # 터치 스크롤 → 마우스 휠 이벤트
  set -g extended-keys on    # Shift+Enter 등 확장 키 전달
  set -g set-clipboard on    # OSC 52로 폰 클립보드 복사
  set -g history-limit 50000
  ```
- 서버가 Windows면 tmux 미지원 → WSL 안에서 sshd + tmux 사용.

---

## 4. 추가 제안 기능

### Claude Code 작업 흐름
- **승인 버튼 오버레이**: 화면에서 Claude의 권한 확인 프롬프트(예: "Do you want to proceed?" + 번호 선택지)를 감지하면 큰 네이티브 버튼(1 / 2 / 3, Esc)을 띄움.
- **Claude 알림**: Claude Code hook(`Notification`, `Stop`)에서 OSC 9/777 시퀀스 출력 → 앱이 안드로이드 알림으로 표시("입력 대기 중", "작업 완료"). 알림 탭 시 해당 세션으로 이동.
- **이미지 전달**: 갤러리/스크린샷 선택 → SFTP로 서버 임시 폴더에 업로드 → 입력창에 경로 자동 삽입. (폰 화면 캡처를 Claude에게 보여줄 때)
- **URL 처리**: 출력 속 URL 탭으로 열기(Claude `/login` 인증 URL 등).
- **포트 포워딩 미리보기**: 서버의 개발 서버(예: `localhost:3000`)를 tsnet 경유 로컬 포워딩 → 폰 브라우저/내장 WebView로 바로 확인.
- **OSC 52 클립보드**: 서버(tmux, Claude)에서 복사한 내용이 폰 클립보드로.
- **작업 중 화면 켜짐 유지** 토글.

### 터미널 기본기
- 스크롤백 검색, 단어/줄 단위 선택, "마지막 출력 복사".
- 여러 세션 탭, 안드로이드 멀티윈도우/분할화면, DeX 대응(창 크기 변경 시 리사이즈 정책 선택).
- 경량 SFTP: 업로드/다운로드, 파일 미리보기.
- 세션 로그 로컬 저장(옵션).
- 고대비 테마, 글꼴 크기 방향별 자동 전환(세로/가로).

### 보안·편의
- 앱 실행 시 생체인증 잠금(옵션).
- 홈 화면 바로가기/위젯: 특정 호스트 + tmux 세션 즉시 접속.
- Foreground Service는 세션이 열려 있을 때만 → 배터리 절약.
- (선택, 나중) Mosh: tsnet이 UDP를 지원하므로 가능하나, tmux + 자동 재연결로 대부분 해결되어 우선순위 낮음.

---

## 5. 단계별 로드맵

| 단계 | 범위 |
|---|---|
| **1단계 (MVP)** | Go 코어(tsnet + ssh), 키 생성/가져오기 + Keystore, 호스트 목록(tailnet 피어), 기본 터미널, K380 키 처리, Claude 키 줄(Enter/줄바꿈 포함), tmux 자동 부착 |
| **2단계** | 가상 넓은 화면, 시각 전용 핀치 줌, 자동 재연결, 입력창, 한글 글꼴·폭 처리, OSC 52, URL 탭 |
| **3단계** | 표 렌즈, 읽기 모드, Claude 알림(OSC 9), 승인 버튼 오버레이, 한글 하드웨어 입력, 키 재배치 UI |
| **4단계** | 이미지 업로드, 포트 포워딩 미리보기, SFTP, 위젯, 멀티윈도우/DeX 최적화, (선택) Mosh |

---

## 6. 라이선스 원칙

- 허용형 라이선스(MIT / BSD / Apache-2.0 / OFL)만 사용. GPL 등 소스 공개 의무 라이선스는 `gen-go-licenses.ps1`의 검사로 차단.
- 새 의존성을 추가하면 **같은 커밋에서** `THIRD_PARTY_NOTICES.md`, `assets/licenses/`, `LicensesScreen.kt`의 목록을 함께 갱신.
- 배포 저장소: `github.com/crossback2000` (최종본 완성 후 공개).

## 7. 결정 필요 사항
- 서버 OS (Linux / macOS / Windows+WSL) → tmux·`AcceptEnv` 설정 방식 결정
- 개인용만인지, 나중에 공개 배포할지 → Termux 터미널 라이브러리(GPLv3) 사용 여부 결정
- 개발 환경: Android Studio, Android SDK/NDK, Go + gomobile 설치 여부
