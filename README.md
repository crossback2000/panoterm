# PanoTerm

폰으로 Claude Code 쓰려고 만든 안드로이드 SSH 앱입니다.

갤럭시 S25 울트라로 Claude Code를 자주 쓰는데, Claude가 표를 그리기 시작하면 답이 없더라고요. 글씨를 줄이면 안 보이고, 키우면 표가 줄바꿈돼서 다 깨지고요. 기존 SSH 앱들은 광고가 붙거나 쓸 만한 기능은 유료라서 그냥 직접 만들었습니다. 이름은 파노라마(Panorama) + 터미널입니다. 넓은 화면을 옆으로 밀어 가며 본다는 뜻으로 지었어요.

광고, 결제, 회원가입, 클라우드 동기화는 없습니다. 앞으로도 넣을 생각 없어요.

## 뭐가 다른가요

**표를 크게 볼 수 있어요.**
화면에 표가 나오면 오른쪽 위에 `▦ 표 N개 크게 보기` 버튼이 뜹니다. 누르면 Claude가 그린 표를 큰 글씨의 진짜 표로 다시 보여줘요. 첫 번째 열은 고정되고 나머지는 옆으로 밀어서 보면 됩니다. 터미널에서 줄바꿈돼 깨진 표도 대부분 원래대로 복원해요.

**화면을 넓게 쓸 수 있어요.**
위쪽 `↔` 버튼을 누르면 서버에는 120열(160, 200열도 가능)이라고 알려주고, 폰에서는 읽기 편한 글씨 크기 그대로 그립니다. 넘치는 부분은 손가락으로 옆으로 밀어서 보면 돼요. 표가 깨지지 않고 원래 모양으로 나옵니다.

**길게 읽을 땐 읽기 모드로요.**
`📖` 버튼을 누르면 Claude의 마지막 답변만 큰 글씨로 따로 보여줍니다. 폭이 좁아도 읽기 편하게 표는 행마다 `▸ 항목: 값` 형태로 풀어서 보여줘요.

글꼴은 D2Coding을 넣었습니다. 한글이 정확히 영문 두 칸이라 한글 섞인 표도 줄이 안 틀어져요.

### Claude Code 쓸 때 편한 것들

- "Do you want to proceed?" 같은 선택지가 나오면 아래에 큰 버튼(1, 2, 3, Esc)이 같이 뜹니다. 손가락으로 번호 맞추기 은근 힘들어서 넣었어요.
- 다른 앱 보고 있을 때 Claude가 입력을 기다리면 알림이 옵니다. 설정 방법은 아래에 있어요.
- `✎`를 누르면 여러 줄 입력창이 열립니다. 삼성 키보드로 한글 쓰거나 음성 입력하기 편해요.
- `⚡`(또는 Ctrl+Shift+P)는 명령 모음이에요. `/compact`, `/clear`, `claude --continue` 같은 걸 눌러서 바로 보냅니다. 목록은 설정에서 바꿀 수 있어요.
- Claude 멈추고 싶을 때 화면을 두 손가락으로 왼쪽으로 쓱 밀면 Esc가 갑니다.
- 위쪽 `⋮` 메뉴에 나머지가 다 있습니다. 사진을 고르면 서버에 올리고 경로를 프롬프트에 넣어주고(스크린샷 보여주면서 물어볼 때), 서버에서 돌고 있는 개발 서버(3000번 포트 같은 거)를 폰 브라우저로 열 수도 있어요.
- 서버에서 복사한 내용이 폰 클립보드로 넘어옵니다(tmux 설정 필요). 출력에 나온 링크는 눌러서 열면 되고요.

### 연결

Tailscale이 앱 안에 들어 있어서 폰에 Tailscale 앱을 따로 깔 필요가 없습니다. VPN 자리를 차지하지 않으니 다른 VPN이랑 같이 써도 돼요. 로그인하면 tailnet 기기 목록이 뜨고, 거기서 바로 호스트를 추가할 수 있습니다.

접속은 SSH 키로만 합니다. 앱에서 Ed25519 키를 만들거나 기존 키를 가져오면 되고, 개인키는 폰의 보안 칩(Keystore)으로 암호화해서 저장해요. 더 안전하게 쓰고 싶으면 키를 만들 때 "하드웨어 키"를 고르세요. 키가 StrongBox 칩 안에서 만들어지고 밖으로 아예 안 나옵니다. 대신 백업은 안 돼요.

접속하면 tmux 세션에 자동으로 붙습니다. 지하철에서 신호가 끊기거나 와이파이가 바뀌어도 알아서 다시 붙으니 Claude 작업이 날아가지 않아요. 자주 쓰는 서버는 홈 화면에 바로가기로 꺼내둘 수 있습니다.

설정에서 앱 잠금(지문/얼굴)이나 "접속할 때 생체인증"을 켤 수 있어요. 후자를 켜면 새 세션을 열 때마다 한 번씩 확인합니다.

폰을 바꿀 때는 설정 → 백업에서 내보내기를 누르면 호스트, 키, 서버 지문, 명령 목록이 암호 걸린 파일 하나로 저장됩니다. 새 폰에서 가져오기 하면 그대로 돌아와요. (하드웨어 키를 쓰던 호스트는 키만 새로 골라주면 됩니다.)

### 업데이트

앱이 GitHub 릴리스를 보고 새 버전이 있으면 알아서 받아서 설치합니다(1.2.0부터).

- 처음 한 번은 "PanoTerm에서 앱 설치 허용"을 켜 달라고 나와요. 설치 확인 창이 한 번 뜰 수도 있고요. 그다음부터는 손댈 필요 없이 조용히 업데이트됩니다.
- 받은 파일은 지금 깔린 앱과 같은 서명인지 확인하고, GitHub에 올라온 해시와도 맞춰본 다음에만 설치해요. 누가 파일을 바꿔치기하면 설치하지 않습니다.
- 설치하면 앱이 다시 시작되면서 SSH 연결이 끊기니까, 열린 세션이 있으면 다 닫을 때까지 기다렸다가 설치합니다.
- 기본은 Wi-Fi에서만 받아요. 설정에서 자동 업데이트를 끄면 새 버전이 나왔다는 알림만 보여줍니다.

### 키보드

로지텍 K380 붙여서 쓰는 걸 기준으로 맞췄습니다.

| 키 | 하는 일 |
|---|---|
| Shift+Tab | Claude 모드 전환 (`ESC [ Z`) |
| Shift+Enter, Alt+Enter | Claude 입력창에서 줄바꿈 |
| Ctrl+Backspace | 단어 지우기 |
| Shift+Space | 한/영 전환 (설정에서 바꿀 수 있어요) |
| Ctrl+Shift+V / C | 붙여넣기 / 복사 |
| Ctrl+Shift+F | 표 크게 보기 |
| Ctrl+Shift+R | 읽기 모드 |
| Ctrl+Shift+E | 입력창 |
| Ctrl+Shift+W | 넓게 보기 전환 |
| Ctrl+Shift+P | 명령 모음 (⚡) |
| Ctrl+Alt+= / − | 글자 크기 |
| Ctrl+Alt+T | tmux 키 줄 보이기/숨기기 |
| Ctrl+Tab | 다음 세션 |
| CapsLock | Ctrl로 쓰기 (설정에서 켜기) |

K380은 F1~F12가 기본으로 볼륨 같은 미디어 키라서, Fn+Esc 한 번 눌러서 Fn 잠금을 걸어두는 게 편합니다.

키보드 없이 쓸 때는 화면 아래 키 줄을 쓰면 됩니다. Esc, ⇧Tab, Esc 두 번, ^C, Tab, Ctrl/Alt(한 번 누르면 다음 글자에만 적용), 방향키(누르고 있으면 반복), 자주 쓰는 기호, 줄바꿈이 있고 오른쪽 끝에 큰 Enter가 고정돼 있어요. `◀ ▶` 키는 누른 채로 좌우로 끌면 커서가 움직입니다. 어떤 키를 어떤 순서로 둘지는 설정 → 화면 키 줄 편집에서 바꿀 수 있어요. tmux 줄(^B, 새 창, 다음, 이전, 확대, 스크롤 등)은 켜고 끌 수 있습니다.

볼륨 아래 키를 Ctrl처럼 쓰는 옵션도 있어요(설정에서 켜기). 한 손으로 ^C 누를 때 편합니다.

## 설치

1. [Releases](../../releases)에서 최신 `PanoTerm-x.y.z.apk`를 받아서 설치합니다. "출처를 알 수 없는 앱" 허용이 필요해요. 안드로이드 12 이상만 됩니다.
2. 앱을 열고 Tailscale 로그인을 누르면 브라우저가 뜹니다. 거기서 승인하면 끝이에요.
3. `SSH 키`에서 키를 하나 만들고 공개키를 복사해서 서버 `~/.ssh/authorized_keys`에 붙여넣습니다.
4. tailnet 기기 목록에서 `+ 추가`를 누르고 사용자 이름이랑 키를 고른 다음 저장하면 됩니다. 처음 접속할 때 서버 지문을 한 번 확인해 달라고 나와요.

## 서버 쪽 설정 (추천)

`~/.tmux.conf`에 이 정도 넣어두면 좋습니다.

```
set -g mouse on
set -g extended-keys on          # Shift+Enter 같은 키가 tmux를 통과하게
set -g set-clipboard on          # 서버에서 복사하면 폰 클립보드로
set -g allow-passthrough on      # 알림이 tmux를 통과하게
set -g history-limit 50000
set -ga update-environment MOBILE_TERM
```

폰으로 접속하면 `MOBILE_TERM=1` 환경변수가 붙습니다. `~/.claude/CLAUDE.md`에 아래처럼 적어두면 Claude가 폰에서는 넓은 표 대신 목록으로 답해줘서 훨씬 보기 편해요.

```
- If the environment variable MOBILE_TERM is set, I'm on a phone: avoid wide tables,
  prefer short bullet lists and keep lines under ~60 characters.
```

알림을 받고 싶으면 [`extras/claude-notify.sh`](extras/claude-notify.sh)를 `~/.claude/`에 복사하고 실행 권한(`chmod +x`)을 준 다음, 파일 안에 적힌 대로 `~/.claude/settings.json`의 hook에 등록하면 됩니다. Claude Code 알림을 터미널 벨로 설정해 둬도 알림으로 바꿔서 보여줍니다.

서버가 윈도우면 tmux는 WSL 안에서 쓰셔야 해요.

tmux는 Homebrew(`/opt/homebrew/bin`), linuxbrew, `~/.local/bin`, nix 경로까지 찾아봅니다. 그래도 못 찾으면 화면 위에 노란 경고가 뜨고 일반 셸로 접속돼요. 이 상태에선 연결이 끊기면 실행 중이던 Claude도 같이 끝나니 tmux를 설치해 두세요.

## 직접 빌드하기

JDK 17, 안드로이드 SDK(platform 35, NDK 포함), Go 1.25 이상이 필요합니다.

```powershell
.\scripts\build-core.ps1        # Go 쪽 테스트 돌리고 core.aar 생성
.\gradlew assembleDebug         # 디버그 빌드 (에뮬레이터에서도 돌아감)
.\gradlew assembleRelease       # 릴리스 빌드
```

릴리스 서명 정보는 저장소 밖에 둡니다. `~/.panoterm/keystore.properties`(또는 `PANOTERM_KEYSTORE_PROPS` 환경변수로 지정한 파일)에서 읽어요.

```
storeFile=C:/Users/<you>/.panoterm/release.jks
storePassword=...
keyAlias=panoterm
keyPassword=...
```

xterm.js, D2Coding, 라이선스 파일은 저장소에 이미 들어 있습니다. 버전을 올릴 때만 `scripts/fetch-assets.ps1`을 돌리면 되고, Go 의존성을 바꿨으면 `scripts/gen-go-licenses.ps1`로 라이선스 목록을 다시 만들어 주세요.

테스트는 이렇게 돌립니다.

```powershell
cd core; go test ./...           # 테스트용 SSH 서버를 띄워서 접속·SFTP·포워딩까지 확인
.\gradlew testDebugUnitTest      # 한글 입력, 알림 감지, 버퍼 테스트
```

## 코드 구조

```
core/                  Go 코어 (gomobile로 app/libs/core.aar를 만듦)
  node.go              Tailscale (tsnet)
  ssh.go, extras.go    SSH 세션, 연결 유지, SFTP 업로드, 포트 포워딩
  keys.go              키 생성/가져오기
  netenv.go            안드로이드 네트워크 정보를 Go에 넘겨주는 부분
app/src/main/
  assets/term/         xterm.js 화면 (넓게 보기, 핀치 줌, 표 찾기, 승인 버튼 감지) + D2Coding
  assets/licenses/     오픈소스 라이선스 원문
  java/dev/panoterm/   안드로이드 앱 (Kotlin, Compose)
extras/                서버에 두는 스크립트 (Claude 알림)
```

## 아직 확인 못 한 것

에뮬레이터와 테스트 서버로는 다 돌려봤는데, 실제 S25 울트라에 K380 붙여서 제 tailnet에 붙는 건 아직 못 해봤습니다. 이상한 점 있으면 이슈로 알려주세요.

## 라이선스

PanoTerm 코드는 [MIT](LICENSE)입니다. 같이 들어 있는 폰트나 라이브러리는 각자 라이선스를 따르고, [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 정리해 뒀어요.

Tailscale은 Tailscale Inc., Claude는 Anthropic의 상표입니다. 이 프로젝트는 두 회사와 아무 관계 없는 개인 프로젝트예요.
