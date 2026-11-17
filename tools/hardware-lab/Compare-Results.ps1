<#
.SYNOPSIS Compares two benchmark summaries (before/after). A change is only called better/worse beyond
-Threshold percent (default 5 %), to avoid reading noise as an improvement.
.EXAMPLE .\Compare-Results.ps1 results\20261120-200000-base results\20261120-201500-new
#>
param([Parameter(Mandatory)] [string] $Before, [Parameter(Mandatory)] [string] $After, [double] $Threshold = 5)
function Load($p) { if (Test-Path (Join-Path $p 'summary.json')) { $p = Join-Path $p 'summary.json' }; Get-Content $p -Raw | ConvertFrom-Json }
$a = Load $Before; $b = Load $After
# +1: higher is better, -1: lower is better
$dir = [ordered]@{
    fps_rx_top_median = 1; fps_rx_bottom_median = 1; fps_presented_median = 1; incomplete_ratio = -1
    dropped_before_decode = -1; decode_errors = -1; decode_p50_ms_median = -1; decode_p95_ms_max = -1; draw_p95_ms_max = -1
    rx_to_display_p50_ms_median = -1; rx_to_display_p95_ms_max = -1; input_send_p95_ms_max = -1
    cpu_percent_median = -1; pss_mb_growth = -1; threads_max = -1; crashes = -1; anr = -1; kbps_median = 0
}
Write-Host "Antes: $($a.label) ($($a.commit))   Después: $($b.label) ($($b.commit))"
$rows = foreach ($k in $dir.Keys) {
    $x = $a.$k; $y = $b.$k
    $verdict = 'igual'
    $pct = $null
    if ($x -ne $null -and $y -ne $null) {
        $x = [double]$x; $y = [double]$y
        if ($x -ne 0) { $pct = [math]::Round(100 * ($y - $x) / [math]::Abs($x), 1) } elseif ($y -ne 0) { $pct = 100 }
        if ($dir[$k] -ne 0 -and $pct -ne $null -and [math]::Abs($pct) -ge $Threshold) {
            $verdict = if ([math]::Sign($pct) -eq $dir[$k]) { 'MEJOR' } else { 'PEOR' }
        }
    } else { $verdict = 'sin dato' }
    [pscustomobject]@{ Metrica = $k; Antes = $a.$k; Despues = $b.$k; 'Cambio%' = $pct; Veredicto = $verdict }
}
$rows | Format-Table -AutoSize
if ($a.samples -lt 10 -or $b.samples -lt 10) { Write-Warning 'Pocas muestras: repite con -DurationSec mayor antes de concluir.' }
if (($rows | Where-Object Veredicto -eq 'PEOR').Count -gt 0) { exit 4 }
