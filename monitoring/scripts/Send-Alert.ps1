# 모든 감시 스크립트가 공용으로 쓰는 알림 발송 모듈.
# 다른 채널(디스코드/이메일 등)로 바꾸려면 Send-Alert 함수 내부만 수정하면 된다.

$script:ConfigPath = Join-Path $PSScriptRoot "config.json"

function Get-MonitorConfig {
    if (-not (Test-Path $script:ConfigPath)) {
        throw "config.json 이 없습니다. config.example.json 을 복사해서 값을 채우세요: $script:ConfigPath"
    }
    Get-Content $script:ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Send-Alert {
    param(
        [Parameter(Mandatory)][string]$Message,
        # 같은 종류의 알람이 쿨다운 시간 내에 반복 발송되지 않도록 하는 식별자.
        # 비워두면 쿨다운 없이 즉시 발송한다.
        [string]$ThrottleKey = ""
    )

    $config = Get-MonitorConfig

    if ($ThrottleKey) {
        $stateDir  = Join-Path $env:ProgramData "MiniPCMonitor"
        $stateFile = Join-Path $stateDir "last-alert-$ThrottleKey.txt"
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
        if (Test-Path $stateFile) {
            $last = Get-Item $stateFile | Select-Object -ExpandProperty LastWriteTime
            $cooldown = [int]$config.alertCooldownMinutes
            if ((Get-Date) -lt $last.AddMinutes($cooldown)) {
                Write-Host "쿨다운 중이라 발송 생략: $ThrottleKey"
                return
            }
        }
        Set-Content -Path $stateFile -Value (Get-Date -Format o)
    }

    $text = "[$($config.hostLabel)] $Message"
    $uri = "https://api.telegram.org/bot$($config.telegramBotToken)/sendMessage"
    try {
        Invoke-RestMethod -Uri $uri -Method Post -Body @{
            chat_id = $config.telegramChatId
            text    = $text
        } -TimeoutSec 15 | Out-Null
        Write-Host "알림 발송됨: $text"
    } catch {
        # 네트워크 장애 등으로 발송 실패 시 이벤트 로그에 남겨 원인 추적 가능하게 한다
        Write-Warning "텔레그램 발송 실패: $_"
        try {
            New-EventLog -LogName Application -Source "MiniPCMonitor" -ErrorAction SilentlyContinue
            Write-EventLog -LogName Application -Source "MiniPCMonitor" -EventId 1001 `
                -EntryType Warning -Message "알림 발송 실패: $text `n$_"
        } catch {}
    }
}
