<# Saves the 3DS2TV-related logcat of the last N minutes (default 10) plus crashes/ANR. #>
param([int] $Minutes = 10, [string] $OutDir)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$serial = Resolve-LabDevice
# One string for the device shell (adb joins arguments with spaces).
$cmd = 'date -d @$(( $(date +%s) - ' + ($Minutes * 60) + " )) '+%m-%d %H:%M:%S.000'"
$since = ((Invoke-Adb -Serial $serial -AdbArgs @('shell', $cmd)) -join '').Trim()
if (-not $OutDir) { $OutDir = New-ResultDir 'logcat' }
Save-Logcat -Serial $serial -DeviceTime $since -Path (Join-Path $OutDir 'logcat.txt')
$c = Get-CrashesSince -Serial $serial -DeviceTime $since
$c | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 (Join-Path $OutDir 'crashes.json')
Write-Host "Guardado en $OutDir  (cierres: $($c.Fatal.Count) líneas, ANR: $($c.Anr.Count))"
