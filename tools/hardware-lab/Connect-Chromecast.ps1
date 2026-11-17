<#
.SYNOPSIS Pairs (optional) and connects adb to the Chromecast over Wi-Fi, then shows device info.
.EXAMPLE  .\Connect-Chromecast.ps1 -HostPort 192.168.1.21:33135
.EXAMPLE  .\Connect-Chromecast.ps1 -PairHostPort 192.168.1.21:35075 -PairCode 858190 -HostPort 192.168.1.21:33135
The ports change whenever wireless debugging is re-enabled: read them on the TV
(Settings > System > Developer options > Wireless debugging).
#>
param([string] $HostPort, [string] $PairHostPort, [string] $PairCode, [switch] $SaveSerial)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$cfg = Get-LabConfig
if (-not $HostPort) { $HostPort = $cfg.chromecastAdbHost }
if ($PairHostPort) {
    if (-not $PairCode) { throw 'Falta -PairCode (código de 6 dígitos de "Vincular dispositivo con código").' }
    Invoke-Adb -NoDevice -AdbArgs @('pair', $PairHostPort, $PairCode) | Write-Host
}
if ($HostPort) {
    Invoke-Adb -NoDevice -AllowFail -AdbArgs @('connect', $HostPort) | Write-Host
    $cfg.chromecastAdbHost = $HostPort
    Save-LabConfig $cfg
}
Get-AdbDevices | Format-Table -AutoSize
$serial = Resolve-LabDevice
$info = Get-DeviceInfo -Serial $serial
$info | Format-List
if ($SaveSerial) { $cfg.deviceSerial = $serial; Save-LabConfig $cfg; Write-Host "deviceSerial = $serial guardado en lab.config.json" }
