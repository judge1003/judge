# 디스크 고장 징후 검사. 작업 스케줄러가 매일 실행한다.
# - Windows 저장소 스택의 HealthStatus (SMART 기반 종합 판정)
# - StorageReliabilityCounter의 교정 불가 오류/마모도/온도
# -Summary 를 붙이면 이상이 없어도 현재 상태 요약을 발송한다.

param([switch]$Summary)

. (Join-Path $PSScriptRoot "Send-Alert.ps1")

$problems = @()
$lines = @()

foreach ($disk in Get-PhysicalDisk) {
    $name = "$($disk.FriendlyName) ($([math]::Round($disk.Size/1GB))GB)"
    $lines += "$name : $($disk.HealthStatus)"

    if ($disk.HealthStatus -ne "Healthy") {
        $problems += "🔴 $name 상태: $($disk.HealthStatus) — 즉시 백업 확인 필요"
        continue
    }

    $r = $disk | Get-StorageReliabilityCounter -ErrorAction SilentlyContinue
    if ($r) {
        if ($r.ReadErrorsUncorrected -gt 0 -or $r.WriteErrorsUncorrected -gt 0) {
            $problems += "🔴 $name : 교정 불가 오류 발생 (읽기 $($r.ReadErrorsUncorrected) / 쓰기 $($r.WriteErrorsUncorrected)) — 디스크 수명 끝나가는 신호"
        }
        if ($null -ne $r.Wear -and $r.Wear -ge 90) {
            $problems += "⚠ $name : SSD 마모도 $($r.Wear)% — 교체 준비 필요"
        }
        if ($null -ne $r.Temperature -and $r.Temperature -ge 60) {
            $problems += "⚠ $name : 온도 $($r.Temperature)°C — 발열 확인 필요"
        }
    }
}

if ($problems.Count -gt 0) {
    Send-Alert -Message ("💾 디스크 이상 감지`n" + ($problems -join "`n"))
} elseif ($Summary) {
    Send-Alert -Message ("💾 디스크 상태 정상`n" + ($lines -join "`n"))
} else {
    Write-Host "디스크 정상"
}
