<#
Semi-automatic: prints state/frames every second while YOU do something on the 3DS or the network
(switch off the 3DS Wi-Fi for 5 s, restart Remote Play, change game...). Measures time to recover.
#>
param([int] $DurationSec = 120)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$serial = Resolve-LabDevice
$lostAt = $null
$end = (Get-Date).AddSeconds($DurationSec)
while ((Get-Date) -lt $end) {
    try { $m = Get-LabMetrics -Serial $serial } catch { Write-Host "sin métricas: $_"; Start-Sleep 1; continue }
    if ($m.state -ne 'STREAMING' -and -not $lostAt) { $lostAt = Get-Date; Write-Host "$(Get-Date -f HH:mm:ss) imagen perdida ($($m.state))" -ForegroundColor Yellow }
    if ($m.state -eq 'STREAMING' -and $lostAt) { Write-Host ("$(Get-Date -f HH:mm:ss) recuperada en {0:N1} s" -f ((Get-Date) - $lostAt).TotalSeconds) -ForegroundColor Green; $lostAt = $null }
    Write-Host ("{0:HH:mm:ss} {1,-12} shown {2} fps · incompletos {3} · NTR {4}" -f (Get-Date), $m.state, $m.fps_presented, $m.frames_incomplete, $m.ntr_request)
    Start-Sleep -Seconds 1
}
