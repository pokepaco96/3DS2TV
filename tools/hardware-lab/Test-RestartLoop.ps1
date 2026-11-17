<# Automatic: start -> wait STREAMING -> stop, N times; fails on crash/ANR or if a start never streams. #>
param([int] $Iterations = 10)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$serial = Resolve-LabDevice
$t0 = Get-DeviceTime -Serial $serial
$fail = 0
for ($i = 1; $i -le $Iterations; $i++) {
    Start-LabSession -Serial $serial | Out-Null
    $ok = $false; $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 20) {
        Start-Sleep -Milliseconds 700
        try { if ((Get-LabMetrics -Serial $serial).state -eq 'STREAMING') { $ok = $true; break } } catch {}
    }
    Stop-LabSession -Serial $serial | Out-Null
    Write-Host ("#{0}: {1} en {2:N1} s" -f $i, $(if ($ok) { 'STREAMING' } else { 'SIN IMAGEN' }), $sw.Elapsed.TotalSeconds)
    if (-not $ok) { $fail++ }
    Start-Sleep -Seconds 2
}
$c = Get-CrashesSince -Serial $serial -DeviceTime $t0
Write-Host "Fallos: $fail/$Iterations · cierres: $($c.Fatal.Count) · ANR: $($c.Anr.Count)"
if ($fail -or $c.Fatal.Count -or $c.Anr.Count) { exit 3 }
