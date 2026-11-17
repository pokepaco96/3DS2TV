<# Prints the live metrics exported by the app (same values as the HUD) as an object/JSON. #>
param([switch] $Json)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$m = Get-LabMetrics
if ($Json) { $m | ConvertTo-Json } else { $m | Format-List }
