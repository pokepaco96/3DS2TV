<# Stops the session normally (neutral input is sent to the 3DS). -Force also force-stops the app (no neutral!). #>
param([switch] $Force)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
Stop-LabSession
if ($Force) {
    Start-Sleep -Seconds 1
    $cfg = Get-LabConfig
    Invoke-Adb -AdbArgs @('shell', 'am', 'force-stop', $cfg.package) | Out-Null
    Write-Warning 'force-stop: Rosalina conserva el último estado de los botones; abre y cierra 3DS2TV o para InputRedirection si algo quedó pulsado.'
}
