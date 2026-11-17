# 3DS2TV hardware lab (Windows 11, PowerShell)

Scripts to build, install, drive and measure 3DS2TV on a **real** Chromecast with Google TV + New 3DS XL.
Full guide: [`docs/HARDWARE_LAB.md`](../../docs/HARDWARE_LAB.md).

1. `.\Check-Requirements.ps1` · 2. `copy lab.config.example.json lab.config.json` (private, git-ignored)
3. `.\Connect-Chromecast.ps1 -HostPort <ip:puerto> -SaveSerial` · 4. `.\Build-Apk.ps1` · 5. `.\Install-Apk.ps1`
6. `.\Run-Benchmark.ps1 -Label base -PerfProfile ULTRA_LOW_LATENCY` · 7. `.\Compare-Results.ps1 results\<a> results\<b>`

Requires a **debug** APK (the lab receiver does not exist in release builds).
