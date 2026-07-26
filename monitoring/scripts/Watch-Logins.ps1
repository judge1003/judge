# 로그인 감시. 작업 스케줄러의 이벤트 트리거(보안 로그 4624/4625)로 즉시 실행된다.
# - 4624 (로그인 성공): knownUsers 에 없는 계정이면 알람
# - 4625 (로그인 실패): 항상 알람 (비밀번호 추측 공격 감지)

. (Join-Path $PSScriptRoot "Send-Alert.ps1")
$config = Get-MonitorConfig

# 시스템 내부 계정은 항상 무시한다
$systemAccounts = @("SYSTEM", "LOCAL SERVICE", "NETWORK SERVICE", "ANONYMOUS LOGON", "-")
# 사람의 로그인에 해당하는 유형만 본다: 2=콘솔, 3=네트워크(SMB 등), 10=원격데스크톱, 11=캐시된 로그인
$humanLogonTypes = @("2", "3", "10", "11")

$since = (Get-Date).AddMinutes(-2)
$events = Get-WinEvent -FilterHashtable @{ LogName = "Security"; Id = 4624, 4625; StartTime = $since } -ErrorAction SilentlyContinue

foreach ($event in $events) {
    $xml = [xml]$event.ToXml()
    $data = @{}
    foreach ($d in $xml.Event.EventData.Data) { $data[$d.Name] = $d.'#text' }

    $user  = $data["TargetUserName"]
    $ip    = if ($data["IpAddress"] -and $data["IpAddress"] -ne "-") { $data["IpAddress"] } else { "로컬" }
    $ltype = $data["LogonType"]

    if ($user -in $systemAccounts) { continue }
    if ($user -like "*$") { continue }            # 컴퓨터 계정 (MACHINE$)
    if ($user -like "DWM-*" -or $user -like "UMFD-*") { continue }  # 윈도우 내부 세션

    if ($event.Id -eq 4625) {
        Send-Alert -Message "🚨 로그인 실패: 계정 '$user' / 접속지 $ip" -ThrottleKey "fail-$user"
        continue
    }

    if ($ltype -notin $humanLogonTypes) { continue }

    if ($user -notin $config.knownUsers) {
        $typeName = switch ($ltype) {
            "2"  { "콘솔(직접)" }
            "3"  { "네트워크(파일공유 등)" }
            "10" { "원격 데스크톱" }
            "11" { "캐시된 로그인" }
            default { "유형 $ltype" }
        }
        Send-Alert -Message "🚨 모르는 사용자 로그인: 계정 '$user' / $typeName / 접속지 $ip"
    }
}
