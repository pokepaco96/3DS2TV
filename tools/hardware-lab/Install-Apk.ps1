<#
Installs (updates) the APK on the selected Chromecast. Never uninstalls by itself: if the signature
differs (APK from another machine/CI), it stops and tells you, because uninstalling wipes settings.
#>
param([string] $Apk = (Join-Path (Join-Path $PSScriptRoot '..\..') 'app\build\outputs\apk\debug\app-debug.apk'))
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
if (-not (Test-Path $Apk)) { throw "No existe $Apk (ejecuta Build-Apk.ps1)" }
$serial = Resolve-LabDevice
$out = Invoke-Adb -Serial $serial -AllowFail -AdbArgs @('install', '-r', $Apk)
$out | Write-Host
if (($out -join ' ') -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Write-Warning "Firma distinta a la instalada. Si aceptas perder los ajustes de 3DS2TV: adb -s $serial uninstall com.threedstwo.tv ; y repite."
    exit 2
}
if (($out -join ' ') -notmatch 'Success') { throw 'La instalación no terminó en Success' }
Get-DeviceInfo -Serial $serial | Format-List
