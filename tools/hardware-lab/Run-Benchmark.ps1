<#
.SYNOPSIS Real-hardware benchmark: starts a session, warms up, samples app metrics + CPU/memory, stops,
collects crashes/ANR and writes results\<date>-<label>\{samples.json, summary.json, summary.md, logcat.txt}.
The 3DS must already be streaming-ready (NTR-HR running, a 3DS game open): the PC cannot control the console.
.EXAMPLE .\Run-Benchmark.ps1 -Label ull-q70 -PerfProfile ULTRA_LOW_LATENCY -DurationSec 120
.EXAMPLE .\Run-Benchmark.ps1 -Label long-30min -DurationSec 1800 -IntervalSec 30
#>
param(
    [Parameter(Mandatory)] [string] $Label,
    [int] $DurationSec = 120, [int] $WarmupSec = 15, [int] $IntervalSec = 5,
    [string] $PerfProfile, [int] $Quality, [int] $Qos, [string] $Audio = 'false', [string] $InputRedirection,
    [string] $View, [string] $Adaptive, [switch] $KeepRunning
)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$serial = Resolve-LabDevice
$info = Get-DeviceInfo -Serial $serial
if (-not $info.AppInstalled) { throw '3DS2TV no está instalada (Install-Apk.ps1).' }
$dir = New-ResultDir $Label
$t0 = Get-DeviceTime -Serial $serial
$o = @{ hud = 'false'; audio = $Audio }   # HUD off: measuring must not change what is measured
if ($PerfProfile) { $o['profile'] = $PerfProfile }
if ($Quality) { $o['quality'] = $Quality }
if ($Qos) { $o['qos'] = $Qos }
if ($InputRedirection) { $o['input'] = $InputRedirection }
if ($View) { $o['view'] = $View }
if ($Adaptive) { $o['adaptive'] = $Adaptive }
Write-Host "Iniciando sesión: $(Start-LabSession -Serial $serial -Overrides $o)"

# wait for real frames (STREAMING = complete frames recently)
$deadline = (Get-Date).AddSeconds(30)
do {
    Start-Sleep -Seconds 2
    try { $m = Get-LabMetrics -Serial $serial } catch { $m = $null }
} until (($m -and $m.state -eq 'STREAMING') -or (Get-Date) -gt $deadline)
if (-not $m -or $m.state -ne 'STREAMING') {
    Save-Logcat -Serial $serial -DeviceTime $t0 -Path (Join-Path $dir 'logcat.txt')
    throw "La sesión no llegó a STREAMING en 30 s (estado: $(if ($m) { $m.state } else { 'sin métricas' })). ¿NTR-HR arrancado y un juego abierto? Ver $dir\logcat.txt"
}
Write-Host "Calentando $WarmupSec s..."
Start-Sleep -Seconds $WarmupSec

$samples = @()
$end = (Get-Date).AddSeconds($DurationSec)
while ((Get-Date) -lt $end) {
    $res = Get-AppResources -Serial $serial -IntervalMs 1000
    $m = Get-LabMetrics -Serial $serial
    $row = [ordered]@{ t = (Get-Date).ToString('o'); cpu_percent = $res.CpuPercent; pss_kb = $res.PssKb; threads = $res.Threads }
    foreach ($p in $m.PSObject.Properties) { $row[$p.Name] = $p.Value }
    $samples += [pscustomobject]$row
    Write-Host ("{0:HH:mm:ss} {1} rx {2}/{3} fps · shown {4} · incompletos {5} · decode p95 {6} ms · rx→disp p95 {7} ms · CPU {8} % · PSS {9} MB" -f (Get-Date),
        $m.state, $m.fps_rx_top, $m.fps_rx_bottom, $m.fps_presented, $m.frames_incomplete, $m.decode_p95_ms, $m.rx_to_display_p95_ms, $res.CpuPercent, [math]::Round($res.PssKb / 1024, 1))
    Start-Sleep -Seconds ([math]::Max(0, $IntervalSec - 2))
}
if (-not $KeepRunning) { Write-Host (Stop-LabSession -Serial $serial) }
Start-Sleep -Seconds 2
$crashes = Get-CrashesSince -Serial $serial -DeviceTime $t0
Save-Logcat -Serial $serial -DeviceTime $t0 -Path (Join-Path $dir 'logcat.txt')
$samples | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 (Join-Path $dir 'samples.json')

$first = $samples[0]; $last = $samples[-1]
$decoded = [double]($last.decoded_frames - $first.decoded_frames)
$incomplete = [double]($last.frames_incomplete - $first.frames_incomplete)
$pss = @($samples | ForEach-Object { [double]$_.pss_kb })
$summary = [ordered]@{
    label = $Label; commit = $last.commit; app_version = $last.app_version; device = $last.device; android = $last.android
    profile = $last.profile; quality_setting = $last.quality_setting; qos_setting = $last.qos_setting; ntr_request = $last.ntr_request
    duration_sec = $DurationSec; samples = $samples.Count
    fps_rx_top_median = Get-Median ($samples | ForEach-Object { [double]$_.fps_rx_top })
    fps_rx_bottom_median = Get-Median ($samples | ForEach-Object { [double]$_.fps_rx_bottom })
    fps_presented_median = Get-Median ($samples | ForEach-Object { [double]$_.fps_presented })
    kbps_median = Get-Median ($samples | ForEach-Object { [double]$_.kbps })
    incomplete_ratio = $(if ($decoded + $incomplete -gt 0) { [math]::Round($incomplete / ($decoded + $incomplete), 4) } else { $null })
    dropped_before_decode = $last.frames_dropped_before_decode - $first.frames_dropped_before_decode
    decode_errors = $last.frames_decode_errors - $first.frames_decode_errors
    decode_p50_ms_median = Get-Median ($samples | ForEach-Object { [double]$_.decode_p50_ms })
    decode_p95_ms_max = Get-Max ($samples | ForEach-Object { [double]$_.decode_p95_ms })
    draw_p95_ms_max = Get-Max ($samples | ForEach-Object { [double]$_.draw_p95_ms })
    rx_to_display_p50_ms_median = Get-Median ($samples | ForEach-Object { [double]$_.rx_to_display_p50_ms })
    rx_to_display_p95_ms_max = Get-Max ($samples | ForEach-Object { [double]$_.rx_to_display_p95_ms })
    input_send_p95_ms_max = Get-Max ($samples | Where-Object { $_.input_send_p95_ms -ne $null } | ForEach-Object { [double]$_.input_send_p95_ms })
    cpu_percent_median = Get-Median ($samples | ForEach-Object { [double]$_.cpu_percent })
    pss_mb_start = [math]::Round($pss[0] / 1024, 1); pss_mb_end = [math]::Round($pss[-1] / 1024, 1)
    pss_mb_growth = [math]::Round(($pss[-1] - $pss[0]) / 1024, 1)
    threads_max = Get-Max ($samples | ForEach-Object { [double]$_.threads })
    crashes = $crashes.Fatal.Count; anr = $crashes.Anr.Count
}
$summary | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $dir 'summary.json')
$md = @("# Benchmark $Label", '', "Commit $($summary.commit) · $($summary.device) · Android $($summary.android) · perfil $($summary.profile) · NTR: $($summary.ntr_request)", '',
        '| Métrica | Valor |', '|---|---|') + ($summary.Keys | ForEach-Object { "| $_ | $($summary[$_]) |" }) +
      @('', '> Latencias internas del Chromecast (recepción→pantalla, mando→envío). La latencia total requiere cámara: docs/HARDWARE_LAB.md.')
$md | Set-Content -Encoding UTF8 (Join-Path $dir 'summary.md')
Write-Host "`nResultados en $dir"
[pscustomobject]$summary | Format-List
if ($summary.crashes -gt 0 -or $summary.anr -gt 0) { Write-Warning "Cierres: $($summary.crashes)  ANR: $($summary.anr) (ver logcat.txt)"; exit 3 }
