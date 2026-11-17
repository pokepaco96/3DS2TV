# 3DS2TV hardware lab - shared functions (Windows PowerShell 5.1+ / PowerShell 7+).
# Everything talks to ONE explicitly selected adb device. Nothing here opens network services.
$ErrorActionPreference = 'Stop'

$script:LabRoot = $PSScriptRoot
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:Receiver = '.lab.LabControlReceiver'

function Get-LabConfig {
    $path = Join-Path $script:LabRoot 'lab.config.json'
    if (-not (Test-Path $path)) {
        Copy-Item (Join-Path $script:LabRoot 'lab.config.example.json') $path
        Write-Warning "Creado $path a partir del ejemplo. Rellena chromecastAdbHost/consoleIp si hace falta."
    }
    $cfg = Get-Content $path -Raw | ConvertFrom-Json
    if (-not $cfg.package) { $cfg | Add-Member -Force NoteProperty package 'com.threedstwo.tv' }
    if (-not $cfg.resultsDir) { $cfg | Add-Member -Force NoteProperty resultsDir 'results' }
    return $cfg
}

function Save-LabConfig($cfg) {
    $cfg | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $script:LabRoot 'lab.config.json')
}

function Get-AdbPath($cfg) {
    if ($cfg -and $cfg.adbPath -and (Test-Path $cfg.adbPath)) { return $cfg.adbPath }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($root) {
            $p = Join-Path $root 'platform-tools\adb.exe'
            if (Test-Path $p) { return $p }
        }
    }
    throw 'No se encuentra adb. Instala Android SDK Platform-Tools o pon "adbPath" en lab.config.json.'
}

# Runs adb against the selected device only. Returns stdout lines; throws on failure.
function Invoke-Adb {
    param([Parameter(Mandatory)] [string[]] $AdbArgs, [string] $Serial, [switch] $NoDevice, [switch] $AllowFail)
    $cfg = Get-LabConfig
    $adb = Get-AdbPath $cfg
    $all = @()
    if (-not $NoDevice) {
        if (-not $Serial) { $Serial = Resolve-LabDevice }
        $all += @('-s', $Serial)
    }
    $all += $AdbArgs
    $out = & $adb @all 2>&1
    $code = $LASTEXITCODE
    $text = ($out | ForEach-Object { "$_" })
    if ($code -ne 0 -and -not $AllowFail) { throw "adb $($AdbArgs -join ' ') falló ($code): $($text -join "`n")" }
    return $text
}

function Get-AdbDevices {
    $lines = Invoke-Adb -NoDevice -AdbArgs @('devices', '-l')
    $devs = @()
    foreach ($l in $lines) {
        if ($l -match '^(\S+)\s+(device|offline|unauthorized)\b(.*)$') {
            $devs += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2]; Info = $Matches[3].Trim() }
        }
    }
    return $devs
}

# Explicit device selection: config deviceSerial, else the only "device", else error (never guesses).
function Resolve-LabDevice {
    $cfg = Get-LabConfig
    $devs = @(Get-AdbDevices | Where-Object { $_.State -eq 'device' })
    if ($cfg.deviceSerial) {
        if ($devs | Where-Object { $_.Serial -eq $cfg.deviceSerial }) { return $cfg.deviceSerial }
        throw "El dispositivo configurado '$($cfg.deviceSerial)' no está conectado. Conectados: $(($devs | ForEach-Object Serial) -join ', ')"
    }
    # The same Chromecast often appears twice (ip:port and the mDNS name): treat that as one if models match.
    if ($devs.Count -eq 1) { return $devs[0].Serial }
    if ($devs.Count -eq 0) { throw 'No hay dispositivos adb. Ejecuta Connect-Chromecast.ps1.' }
    throw "Hay $($devs.Count) dispositivos adb: $(($devs | ForEach-Object { $_.Serial + ' ' + $_.Info }) -join ' | '). Pon 'deviceSerial' en lab.config.json."
}

function Get-DeviceInfo {
    param([string] $Serial)
    $p = { param($k) (Invoke-Adb -Serial $Serial -AdbArgs @('shell', 'getprop', $k)) -join '' }
    $cfg = Get-LabConfig
    $pkg = Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('shell', 'dumpsys', 'package', $cfg.package)
    $ver = ($pkg | Select-String 'versionName=' | Select-Object -First 1)
    [pscustomobject]@{
        Serial       = $Serial
        Model        = & $p 'ro.product.model'
        Device       = & $p 'ro.product.device'
        Android      = & $p 'ro.build.version.release'
        Api          = & $p 'ro.build.version.sdk'
        AppInstalled = [bool]$ver
        AppVersion   = $(if ($ver) { ($ver.ToString().Trim() -replace '.*versionName=', '') } else { '' })
    }
}

function Get-DeviceTime {
    param([string] $Serial)
    # adb joins arguments with spaces and runs them in the device shell: quote values that contain spaces.
    return ((Invoke-Adb -Serial $Serial -AdbArgs @('shell', "date '+%m-%d %H:%M:%S.000'")) -join '').Trim()
}

function Get-AppPid {
    param([string] $Serial)
    $cfg = Get-LabConfig
    $out = (Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('shell', 'pidof', $cfg.package)) -join ''
    if ($out -match '^\s*(\d+)') { return [int]$Matches[1] }
    return $null
}

function Send-LabBroadcast {
    param([string] $Serial, [Parameter(Mandatory)] [string] $Action, [hashtable] $Extras = @{})
    $cfg = Get-LabConfig
    $a = @('shell', 'am', 'broadcast', '-n', "$($cfg.package)/$($script:Receiver)", '-a', "com.threedstwo.tv.debug.$Action")
    foreach ($k in $Extras.Keys) { $a += @('--es', $k, "$($Extras[$k])") }
    $out = Invoke-Adb -Serial $Serial -AdbArgs $a
    $data = ($out | Select-String 'data="(.*)"' | ForEach-Object { $_.Matches[0].Groups[1].Value }) -join ''
    if (-not $data) { throw "Sin respuesta del receptor de laboratorio (¿APK release o no instalada?): $($out -join ' ')" }
    if ($data -like 'ERROR*') { throw $data }
    return $data
}

function Start-LabSession {
    param([string] $Serial, [hashtable] $Overrides = @{})
    $cfg = Get-LabConfig
    if (-not $Overrides.ContainsKey('ip') -and $cfg.consoleIp) { $Overrides['ip'] = $cfg.consoleIp }
    if (-not $Overrides.ContainsKey('profile') -and $cfg.defaultProfile) { $Overrides['profile'] = $cfg.defaultProfile }
    $r = Send-LabBroadcast -Serial $Serial -Action 'START' -Extras $Overrides
    Invoke-Adb -Serial $Serial -AdbArgs @('shell', 'am', 'start', '-n', "$($cfg.package)/.MainActivity", '--ez', 'lab_autostart', 'true') | Out-Null
    return $r
}

function Stop-LabSession {
    param([string] $Serial)
    try { return Send-LabBroadcast -Serial $Serial -Action 'STOP' } catch { return "STOP: $_" }
}

function Get-LabMetrics {
    param([string] $Serial)
    $r = Send-LabBroadcast -Serial $Serial -Action 'EXPORT_METRICS'
    $path = ($r -replace '^OK\s+', '').Trim()
    $json = (Invoke-Adb -Serial $Serial -AdbArgs @('exec-out', 'cat', $path)) -join ''
    return $json | ConvertFrom-Json
}

# CPU% (of one core) from /proc/<pid>/stat deltas and PSS from dumpsys meminfo. Light: no tracing.
function Get-AppResources {
    param([string] $Serial, [int] $IntervalMs = 1000)
    $cfg = Get-LabConfig
    $appPid = Get-AppPid -Serial $Serial
    if (-not $appPid) { return [pscustomobject]@{ Pid = $null; CpuPercent = $null; PssKb = $null; Threads = $null } }
    $read = {
        $s = (Invoke-Adb -Serial $Serial -AdbArgs @('shell', 'cat', "/proc/$appPid/stat")) -join ' '
        $f = ($s -replace '^.*\)\s+', '') -split '\s+'
        # after "(comm)": state=f[0], utime=f[11], stime=f[12], num_threads=f[17]
        [pscustomobject]@{ Ticks = [long]$f[11] + [long]$f[12]; Threads = [int]$f[17]; At = [DateTime]::UtcNow }
    }
    try {
        $a = & $read
        Start-Sleep -Milliseconds $IntervalMs
        $b = & $read
    } catch {
        # /proc not readable on this build: report memory only
        $a = $null; $b = $null
    }
    $hz = [int](((Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('shell', 'getconf', 'CLK_TCK')) -join '').Trim())
    if ($hz -le 0) { $hz = 100 }
    $cpu = $null; $threads = $null
    if ($a -and $b) {
        $secs = ($b.At - $a.At).TotalSeconds
        $cpu = [math]::Round(100.0 * ($b.Ticks - $a.Ticks) / $hz / $secs, 1)
        $threads = $b.Threads
    }
    $mem = Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('shell', 'dumpsys', 'meminfo', $cfg.package)
    $pss = $null
    $tot = $mem | Select-String '^\s*TOTAL(\s+PSS:)?\s+(\d+)' | Select-Object -First 1
    if ($tot) { $pss = [long]$tot.Matches[0].Groups[2].Value }
    [pscustomobject]@{ Pid = $appPid; CpuPercent = $cpu; PssKb = $pss; Threads = $threads }
}

function Get-CrashesSince {
    param([string] $Serial, [Parameter(Mandatory)] [string] $DeviceTime)
    $cfg = Get-LabConfig
    $t = "'$DeviceTime'"   # contains a space: quoted for the device shell
    $crash = Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('logcat', '-d', '-b', 'crash', '-T', $t)
    $anr = Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('logcat', '-d', '-T', $t, '-s', 'ActivityManager:E')
    [pscustomobject]@{
        Fatal = @($crash | Where-Object { $_ -match 'FATAL EXCEPTION|Process: ' + [regex]::Escape($cfg.package) })
        Anr   = @($anr | Where-Object { $_ -match 'ANR in ' + [regex]::Escape($cfg.package) })
    }
}

function Save-Logcat {
    param([string] $Serial, [Parameter(Mandatory)] [string] $DeviceTime, [Parameter(Mandatory)] [string] $Path)
    Invoke-Adb -Serial $Serial -AllowFail -AdbArgs @('logcat', '-d', '-T', "'$DeviceTime'", '-s', '3DS2TV', '3DS2TV-LAB', 'AndroidRuntime', 'ActivityManager') |
        Set-Content -Encoding UTF8 $Path
}

function New-ResultDir([string] $Label) {
    $cfg = Get-LabConfig
    $base = $cfg.resultsDir
    if (-not [System.IO.Path]::IsPathRooted($base)) { $base = Join-Path $script:LabRoot $base }
    $safe = ($Label -replace '[^A-Za-z0-9_.-]', '_')
    $dir = Join-Path $base ("{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $safe)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

function Get-Median([double[]] $v) {
    $s = @($v | Where-Object { $_ -ne $null } | Sort-Object)
    if ($s.Count -eq 0) { return $null }
    return $s[[int][math]::Floor(($s.Count - 1) / 2)]
}

function Get-Max([double[]] $v) {
    $s = @($v | Where-Object { $_ -ne $null })
    if ($s.Count -eq 0) { return $null }
    return ($s | Measure-Object -Maximum).Maximum
}

Export-ModuleMember -Function * -Variable LabRoot, RepoRoot
