<# JVM micro-benchmarks of the pipeline stages (no device needed). Saves the BENCH lines. #>
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$dir = New-ResultDir 'jvm-bench'
Push-Location $RepoRoot
try {
    $out = & .\gradlew.bat --console=plain :core:test -Pbench --tests '*bench*' --rerun-tasks 2>&1
    $lines = $out | Where-Object { "$_" -match 'BENCH' } | ForEach-Object { ("$_").Trim() }
    $lines | Set-Content -Encoding UTF8 (Join-Path $dir 'bench.txt')
    $lines | Write-Host
    Write-Host "Guardado en $dir (commit $((& git rev-parse --short HEAD).Trim()))"
} finally { Pop-Location }
