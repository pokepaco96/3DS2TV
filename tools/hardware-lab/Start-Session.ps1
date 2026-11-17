<#
Starts a 3DS2TV session on the Chromecast (debug build). Overrides are passed to the lab receiver.
.EXAMPLE .\Start-Session.ps1 -PerfProfile ULTRA_LOW_LATENCY -Audio false -View BOTH
#>
param([string] $ConsoleIp, [string] $PerfProfile, [int] $Quality, [int] $Qos, [string] $Audio, [string] $Hud,
      [string] $InputRedirection, [string] $View, [string] $Layout, [string] $Adaptive, [string] $Mode)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$o = @{}
if ($ConsoleIp) { $o['ip'] = $ConsoleIp }
if ($PerfProfile) { $o['profile'] = $PerfProfile }
if ($Quality) { $o['quality'] = $Quality }
if ($Qos) { $o['qos'] = $Qos }
if ($Audio) { $o['audio'] = $Audio }
if ($Hud) { $o['hud'] = $Hud }
if ($InputRedirection) { $o['input'] = $InputRedirection }
if ($View) { $o['view'] = $View }
if ($Layout) { $o['layout'] = $Layout }
if ($Adaptive) { $o['adaptive'] = $Adaptive }
if ($Mode) { $o['mode'] = $Mode }
Start-LabSession -Overrides $o
