<# Builds the DEBUG APK (the lab receiver exists only in debug) and runs the fast JVM tests first. #>
param([switch] $SkipTests)
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
Push-Location $RepoRoot
try {
    $tasks = @(':app:assembleDebug')
    if (-not $SkipTests) { $tasks = @(':core:test', ':tools:mock-3ds-sender:test', ':app:testDebugUnitTest') + $tasks }
    & .\gradlew.bat --console=plain @tasks
    if ($LASTEXITCODE -ne 0) { throw "gradlew falló ($LASTEXITCODE)" }
    $apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
    $commit = (& git rev-parse --short=12 HEAD).Trim()
    Write-Host "APK: $apk`nSHA-256: $hash`ncommit: $commit"
    [pscustomobject]@{ Apk = $apk; Sha256 = $hash; Commit = $commit }
} finally { Pop-Location }
