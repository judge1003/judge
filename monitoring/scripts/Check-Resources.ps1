# CPU / 메모리 / 디스크 공간 사용률을 검사해서 임계치(기본 90%) 이상이면 알림.
# 작업 스케줄러가 5분마다 실행한다. -Test 를 붙이면 임계치와 무관하게 현재 상태를 발송.

param([switch]$Test)

. (Join-Path $PSScriptRoot "Send-Alert.ps1")
$config = Get-MonitorConfig

# CPU: 순간값은 튀기 쉬우므로 5초 간격 6회 평균(30초)으로 판단한다
$cpuSamples = Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 5 -MaxSamples 6
$cpu = [math]::Round(($cpuSamples.CounterSamples.CookedValue | Measure-Object -Average).Average, 1)

$os = Get-CimInstance Win32_OperatingSystem
$mem = [math]::Round((1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize) * 100, 1)

$diskLines = @()
$diskAlerts = @()
Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
    if ($_.Size -gt 0) {
        $usedPct = [math]::Round((1 - $_.FreeSpace / $_.Size) * 100, 1)
        $freeGB  = [math]::Round($_.FreeSpace / 1GB, 1)
        $diskLines += "$($_.DeviceID) $usedPct% 사용 (남은 공간 ${freeGB}GB)"
        if ($usedPct -ge [double]$config.diskSpaceThresholdPercent) {
            $diskAlerts += "$($_.DeviceID) 드라이브 $usedPct% 사용 중 (남은 공간 ${freeGB}GB)"
        }
    }
}

if ($Test) {
    Send-Alert -Message ("모니터링 테스트`nCPU: $cpu%`nMEM: $mem%`n" + ($diskLines -join "`n"))
    return
}

if ($cpu -ge [double]$config.cpuThresholdPercent) {
    Send-Alert -Message "⚠ CPU 사용률 높음: $cpu% (30초 평균)" -ThrottleKey "cpu"
}
if ($mem -ge [double]$config.memThresholdPercent) {
    Send-Alert -Message "⚠ 메모리 사용률 높음: $mem%" -ThrottleKey "mem"
}
foreach ($alert in $diskAlerts) {
    Send-Alert -Message "⚠ 디스크 공간 부족: $alert" -ThrottleKey ("disk-" + $alert.Substring(0,1))
}
