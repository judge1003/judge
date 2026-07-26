# 미니PC 모니터링 (Windows 11 Pro)

미니PC를 NAS처럼 24시간 운영할 때 필요한 감시/알람 구성입니다.

| 감시 항목 | 방법 | 알림 |
|---|---|---|
| 실시간 대시보드 (CPU/MEM/디스크/네트워크 그래프) | **Netdata** (웹 대시보드) | - |
| CPU / 메모리 사용률 90% 이상 | `Check-Resources.ps1` (5분마다) | 텔레그램 |
| 디스크 공간 90% 이상 | `Check-Resources.ps1` (5분마다) | 텔레그램 |
| 디스크 고장 징후 (SMART/HealthStatus) | `Check-DiskHealth.ps1` (매일 09:00) | 텔레그램 |
| 모르는 사용자 로그인 / 로그인 실패 | `Watch-Logins.ps1` (이벤트 즉시 트리거) | 텔레그램 |
| **전원 on/off, 네트워크 단절** | **Uptime Kuma** (기존 시놀로지 NAS에서 미니PC를 감시) | 텔레그램 |

> 전원 off는 꺼진 PC가 스스로 알릴 수 없으므로 반드시 **다른 장비(기존 NAS)** 에서
> 감시해야 합니다. 그래서 Uptime Kuma만 시놀로지 쪽에 설치합니다.

---

## 1. 텔레그램 봇 만들기 (5분, 최초 1회)

1. 텔레그램에서 `@BotFather` 검색 → `/newbot` → 봇 이름 입력 → **토큰** 받기
   (예: `1234567890:AAHxxxxxxxxxxxxxxxxxxxx`)
2. 만든 봇에게 아무 메시지나 한 번 보내기
3. 브라우저에서 `https://api.telegram.org/bot<토큰>/getUpdates` 열기
   → `"chat":{"id":123456789...` 의 숫자가 **chat id**

## 2. 미니PC에 알람 스크립트 설치

1. 이 저장소의 `monitoring/scripts/` 폴더를 미니PC로 복사 (예: `C:\Monitor\`)
2. `config.example.json`을 `config.json`으로 복사하고 값 채우기:
   - `telegramBotToken`, `telegramChatId`: 위에서 만든 값
   - `knownUsers`: 정상 사용자 계정 목록 (여기 없는 계정이 로그인하면 알람)
3. **관리자 PowerShell**에서:

```powershell
Set-ExecutionPolicy -Scope LocalMachine RemoteSigned
cd C:\Monitor
.\Install-MonitoringTasks.ps1
```

설치 스크립트가 작업 스케줄러에 3개 작업을 등록하고 테스트 알림을 보냅니다.

## 3. Netdata 대시보드 설치 (미니PC)

관리자 PowerShell에서:

```powershell
winget install Netdata.Netdata
```

설치 후 브라우저에서 `http://<미니PC IP>:19999` → 실시간 CPU/MEM/디스크/네트워크 그래프.
(외부에서 보려면 Tailscale/Cloudflare Tunnel 뒤에 두세요. 그대로 포트를 열지 마세요.)

## 4. Uptime Kuma 설치 (기존 시놀로지 NAS)

시놀로지 Container Manager(구 Docker)에서 `monitoring/uptime-kuma/docker-compose.yml`로
프로젝트 생성 → `http://<NAS IP>:3001` 접속 → 관리자 계정 생성 후 모니터 추가:

| 모니터 | 종류 | 대상 | 의미 |
|---|---|---|---|
| 미니PC 전원/네트워크 | Ping | 미니PC IP | 끊기면 = 꺼짐/네트워크 장애 |
| Immich | HTTP | `http://미니PC:2283` | 사진 서버 생존 |
| Nextcloud | HTTP | `http://미니PC:8080` | 파일 서버 생존 |
| 세탁실 대시보드 | HTTP | `http://미니PC:3033/Dashboard/laundry/` | (이전하면) |

설정 → 알림(Notification) → Telegram 추가(같은 봇 토큰/chat id 사용) 후
각 모니터에 연결하면 다운/복구 시 텔레그램이 옵니다.

---

## 동작 확인

- `C:\Monitor\Check-Resources.ps1 -Test` : 강제로 테스트 알림 발송
- `C:\Monitor\Check-DiskHealth.ps1 -Summary` : 현재 디스크 상태 요약을 즉시 발송
- 미니PC LAN 케이블을 뽑아보기 → 1~2분 내 Uptime Kuma 알람 확인

## 알림 채널을 바꾸고 싶다면

모든 알림은 `Send-Alert.ps1` 한 곳을 거칩니다. 텔레그램 대신 디스코드/이메일로
바꾸려면 이 파일의 전송 부분만 수정하면 됩니다.
