<# CPU (% of one core), PSS memory and thread count of 3DS2TV, sampled N times. #>
param([int] $Samples = 5, [int] $IntervalMs = 1000)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$serial = Resolve-LabDevice
1..$Samples | ForEach-Object { Get-AppResources -Serial $serial -IntervalMs $IntervalMs } | Format-Table -AutoSize
