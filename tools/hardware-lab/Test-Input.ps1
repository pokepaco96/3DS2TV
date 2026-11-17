<#
MANUAL check of Input Redirection with the real controller (the PC cannot press 3DS buttons).
Shows the input path metrics while you test each button following the prompts.
#>
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$serial = Resolve-LabDevice
Write-Host '1) En la 3DS: L+Abajo+Select > Miscellaneous options > Start InputRedirection'
Write-Host '2) Sesión con Input Redirection activo:'
Start-LabSession -Serial $serial -Overrides @{ input = 'true' } | Write-Host
foreach ($b in @('A', 'B', 'X', 'Y', 'L', 'R', 'ZL', 'ZR', 'START', 'SELECT', 'Cruceta', 'Circle Pad', 'C-Stick')) {
    $r = Read-Host "Pulsa $b en el mando. ¿Respondió el juego? (s/n)"
    $m = Get-LabMetrics -Serial $serial
    "{0,-10} {1}   mando: {2} · input: {3} · mando→envío p95 {4} ms" -f $b, $r, $m.controller, $m.input, $m.input_send_p95_ms | Write-Host
}
