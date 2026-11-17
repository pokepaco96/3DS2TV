<# Checks the Windows tooling needed by the lab. Changes nothing; prints what is missing. #>
Import-Module (Join-Path $PSScriptRoot 'Lab.psm1') -Force
$ok = $true
function Report($name, $good, $detail) {
    $mark = if ($good) { '[OK]  ' } else { '[FALTA]' }
    Write-Host ("{0} {1,-28} {2}" -f $mark, $name, $detail)
    if (-not $good) { $script:ok = $false }
}
$git = Get-Command git -ErrorAction SilentlyContinue
Report 'Git' ([bool]$git) $(if ($git) { (& git --version) } else { 'https://git-scm.com/download/win' })

$javaOk = $false; $javaDetail = 'Instala JDK 17+ (p. ej. Eclipse Temurin 17) y define JAVA_HOME'
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    $v = (& java -version 2>&1 | Select-Object -First 1) -join ''
    if ($v -match '"(\d+)') { $javaOk = [int]$Matches[1] -ge 17 }
    $javaDetail = "$v  JAVA_HOME=$env:JAVA_HOME"
}
Report 'Java JDK 17+' $javaOk $javaDetail

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -and (Test-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
Report 'Android SDK' ([bool]$sdk -and (Test-Path $sdk)) "ANDROID_HOME=$env:ANDROID_HOME  usado=$sdk"
if ($sdk) {
    Report 'SDK Platform 35' (Test-Path (Join-Path $sdk 'platforms\android-35')) 'sdkmanager "platforms;android-35"'
    Report 'Platform-Tools (adb)' (Test-Path (Join-Path $sdk 'platform-tools\adb.exe')) 'sdkmanager "platform-tools"'
}
try { $adb = Get-AdbPath (Get-LabConfig); Report 'adb' $true ((& $adb version | Select-Object -First 1) -join '') } catch { Report 'adb' $false "$_" }
Report 'gradlew.bat' (Test-Path (Join-Path $RepoRoot 'gradlew.bat')) $RepoRoot
$lp = Join-Path $RepoRoot 'local.properties'
Report 'local.properties (sdk.dir)' ((Test-Path $lp) -or [bool]$env:ANDROID_HOME) 'Se crea con: "sdk.dir=C:\\Users\\<tú>\\AppData\\Local\\Android\\Sdk"'
if ($ok) { Write-Host "`nTodo listo." -ForegroundColor Green } else { Write-Host "`nFalta algo: instálalo y vuelve a ejecutar (este script no instala nada)." -ForegroundColor Yellow; exit 1 }
