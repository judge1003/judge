# 작업 스케줄러에 감시 작업 3개를 등록한다. 관리자 PowerShell에서 1회 실행.
#  1) MiniPC-ResourceCheck : 5분마다 CPU/MEM/디스크 공간 검사
#  2) MiniPC-DiskHealth    : 매일 09:00 디스크 고장 징후 검사
#  3) MiniPC-LoginWatch    : 보안 이벤트 4624/4625 발생 즉시 로그인 검사

#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot

if (-not (Test-Path (Join-Path $scriptDir "config.json"))) {
    throw "config.json 이 없습니다. config.example.json 을 복사해 값을 채운 뒤 다시 실행하세요."
}

# 로그인 실패(4625) 이벤트가 기록되도록 감사 정책을 켠다
auditpol /set /subcategory:"Logon" /success:enable /failure:enable | Out-Null

$psExe = (Get-Command powershell.exe).Source
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 10)

function New-MonitorAction([string]$scriptName) {
    New-ScheduledTaskAction -Execute $psExe `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$scriptDir\$scriptName`"" `
        -WorkingDirectory $scriptDir
}

# 1) 리소스 검사: 5분마다
Register-ScheduledTask -TaskName "MiniPC-ResourceCheck" -Force `
    -Action (New-MonitorAction "Check-Resources.ps1") `
    -Trigger (New-ScheduledTaskTrigger -Once -At (Get-Date) `
        -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration ([TimeSpan]::MaxValue)) `
    -Principal $principal -Settings $settings | Out-Null
Write-Host "등록됨: MiniPC-ResourceCheck (5분마다)"

# 2) 디스크 건강 검사: 매일 09:00
Register-ScheduledTask -TaskName "MiniPC-DiskHealth" -Force `
    -Action (New-MonitorAction "Check-DiskHealth.ps1") `
    -Trigger (New-ScheduledTaskTrigger -Daily -At "09:00") `
    -Principal $principal -Settings $settings | Out-Null
Write-Host "등록됨: MiniPC-DiskHealth (매일 09:00)"

# 3) 로그인 감시: 보안 이벤트 트리거는 XML로만 등록 가능하다
$loginTaskXml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Triggers>
    <EventTrigger>
      <Enabled>true</Enabled>
      <Subscription>&lt;QueryList&gt;&lt;Query Id="0" Path="Security"&gt;&lt;Select Path="Security"&gt;*[System[(EventID=4624 or EventID=4625)]]&lt;/Select&gt;&lt;/Query&gt;&lt;/QueryList&gt;</Subscription>
    </EventTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>S-1-5-18</UserId>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT10M</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>$psExe</Command>
      <Arguments>-NoProfile -ExecutionPolicy Bypass -File "$scriptDir\Watch-Logins.ps1"</Arguments>
      <WorkingDirectory>$scriptDir</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"@
Register-ScheduledTask -TaskName "MiniPC-LoginWatch" -Xml $loginTaskXml -Force | Out-Null
Write-Host "등록됨: MiniPC-LoginWatch (로그인 이벤트 즉시)"

# 설치 확인용 테스트 알림
. (Join-Path $scriptDir "Send-Alert.ps1")
Send-Alert -Message "✅ 모니터링 설치 완료. 감시 시작: CPU/MEM/디스크(5분), 디스크 건강(매일), 로그인(즉시)"
Write-Host "`n설치 완료. 텔레그램으로 테스트 알림이 도착했는지 확인하세요."
