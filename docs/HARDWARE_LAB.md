# Laboratorio de pruebas físicas (Windows 11)

Objetivo: compilar, instalar, arrancar y **medir** 3DS2TV en tu Chromecast real con tu New 3DS XL real, y
comparar cada cambio contra una línea base. El vídeo va siempre **3DS → Chromecast → TV**; el PC solo
controla por ADB y registra.

> Estado: los scripts están escritos para Windows PowerShell 5.1 / PowerShell 7 pero **no se han podido
> ejecutar en el entorno donde se crearon** (sin PowerShell ni dispositivos). La parte Android (receptor de
> laboratorio, exportación JSON) sí está compilada y cubierta por tests (JVM + instrumentado). Ejecuta primero
> `Check-Requirements.ps1` y `Connect-Chromecast.ps1` y corrige lo que falle.

## 1. Requisitos (Check-Requirements.ps1 — no instala nada)

| Herramienta | Fuente oficial |
|---|---|
| Git | https://git-scm.com/download/win |
| JDK 17+ (`JAVA_HOME`) | p. ej. Eclipse Temurin 17 (adoptium.net) |
| Android SDK: platform 35 + platform-tools | Android Studio o `sdkmanager` (developer.android.com/tools) |
| `local.properties` con `sdk.dir=C:\\Users\\<tú>\\AppData\\Local\\Android\\Sdk` | — |

```powershell
cd <repo>\tools\hardware-lab
Set-ExecutionPolicy -Scope Process Bypass     # solo para esta ventana
.\Check-Requirements.ps1
```

## 2. Chromecast por ADB

En la TV: Ajustes → Sistema → Opciones para desarrolladores → **Depuración inalámbrica** (los puertos cambian
cada vez que se reactiva).

```powershell
.\Connect-Chromecast.ps1 -PairHostPort <ip>:<puertoVinculación> -PairCode <código>   # solo la primera vez
.\Connect-Chromecast.ps1 -HostPort <ip>:<puertoConexión> -SaveSerial
```
`-SaveSerial` guarda el dispositivo en `lab.config.json` (privado, ignorado por Git): todos los scripts usan
`adb -s <serial>` y **se niegan a actuar** si hay varios dispositivos sin uno elegido. El mismo Chromecast
suele aparecer dos veces (`ip:puerto` y `adb-…._adb-tls-connect._tcp`): elige uno.

Muestra modelo, Android, API y si 3DS2TV está instalada.

## 3. Compilar e instalar (siempre APK **debug**)

```powershell
.\Build-Apk.ps1          # tests JVM + assembleDebug; imprime SHA-256 y commit
.\Install-Apk.ps1        # adb install -r; nunca desinstala por su cuenta
```
Si dice firma distinta (APK de CI frente a local), desinstalar borra los ajustes: el script te da el comando y
no lo ejecuta.

## 4. Preparar la 3DS (manual: el PC no puede controlar la consola)

1. Wi-Fi en la misma red. BootNTRSelector → **3.6 HR**. **No** inicies Remote Play desde X+Y.
2. Abre un **juego de 3DS** (no DS/GBA).
3. Para pruebas de mando: L+↓+Select → Miscellaneous options → Start InputRedirection.
4. Pon la IP de la 3DS en `lab.config.json` → `consoleIp` (o pásala con `-ConsoleIp`).

## 5. Control de la app (solo builds debug)

Receptor `com.threedstwo.tv/.lab.LabControlReceiver`, protegido con `android.permission.DUMP` (lo tiene
`adb shell`, ninguna app normal); **no existe en el APK release** (comprobado con aapt2/dexdump).

| Acción | Script | Comando ADB equivalente |
|---|---|---|
| Ajustes + arrancar | `Start-Session.ps1 -PerfProfile ULTRA_LOW_LATENCY -Audio false -View BOTH` | `am broadcast -n com.threedstwo.tv/.lab.LabControlReceiver -a com.threedstwo.tv.debug.START --es profile ULTRA_LOW_LATENCY` y `am start -n com.threedstwo.tv/.MainActivity --ez lab_autostart true` |
| Detener (envía estado neutro a la 3DS) | `Stop-Session.ps1` | `… -a com.threedstwo.tv.debug.STOP` |
| Métricas JSON | `Get-Metrics.ps1` | `… -a com.threedstwo.tv.debug.EXPORT_METRICS` → `Android/data/com.threedstwo.tv/files/lab/latest.json` |

Claves de START: `ip, mode (ACTIVE/PASSIVE), profile (ULTRA_LOW_LATENCY/BALANCED/QUALITY/CUSTOM), quality (10–100),
qos (4–20), audio, hud, input, adaptive (true/false), view (TOP_ONLY/BOTTOM_ONLY/BOTH), layout (VERTICAL/HORIZONTAL)`.
Valores inválidos → `ERROR …` y no se aplica nada. Android 10+ no deja abrir actividades desde un broadcast en
segundo plano: por eso el arranque es un `am start` de la actividad del launcher con el extra `lab_autostart`.

Coste en ejecución: cero por fotograma (el JSON se genera solo cuando lo pides, a partir de las métricas que la
app ya calcula cada 500 ms).

## 6. Benchmark real

```powershell
.\Run-Benchmark.ps1 -Label base-ull -PerfProfile ULTRA_LOW_LATENCY -DurationSec 120
```
- Arranca la sesión con el **HUD apagado** (medir no debe cambiar lo medido), espera a STREAMING (frames
  completos), calienta 15 s y muestrea cada 5 s: métricas de la app + CPU (% de un núcleo, de `/proc/<pid>/stat`)
  + memoria PSS (`dumpsys meminfo`) + hilos. No usa grabación de pantalla ni trazas.
- Guarda `results\<fecha>-<label>\`: `samples.json`, `summary.json`, `summary.md`, `logcat.txt`; detecta cierres y
  ANR (sale con código 3).

| Métrica | Fuente |
|---|---|
| FPS recibidos sup/inf, FPS presentados (frames nuevos), kbit/s | Contadores de la app |
| Ratio de frames incompletos, descartados antes de decodificar, errores | Ensamblador / buzón / decodificador |
| Decodificación p50/p95, dibujo p95, recepción→pantalla p50/p95 | Ventanas de 240 muestras en la app |
| Mando→envío p95 | Emisor de Input Redirection (solo con mando y input activos) |
| CPU, PSS, crecimiento de memoria, hilos | ADB |

**No miden la latencia total.** Para eso:

### Latencia real con cámara (240 fps)
1. Móvil en cámara lenta 240 fps encuadrando la pantalla de la 3DS y la TV (TV en "modo juego").
2. Vídeo: provoca un cambio brusco en la 3DS (abrir un menú) → cuenta fotogramas de cámara hasta que cambia la TV.
3. Mando total: pulsa A en el mando → cuenta hasta la reacción en la TV.
4. Mando→consola: pulsa A → hasta la reacción en la pantalla de la 3DS.
5. ms = fotogramas × 4,17. 10 repeticiones por perfil; anota mediana y peor caso en `HARDWARE_VALIDATION.md`.

## 7. Batería de pruebas

| # | Prueba | Script | Automática |
|---|---|---|---|
| 1 | Streaming estable | `Run-Benchmark.ps1 -DurationSec 300` | Sí (3DS preparada) |
| 2 | Calidad variable | `Run-Benchmark.ps1 -Quality 60/70/80/90 -Adaptive false` | Sí |
| 3 | Sesión larga | `Run-Benchmark.ps1 -DurationSec 1800 -IntervalSec 30` (mira `pss_mb_growth`, `threads_max`) | Sí |
| 4 | Cerrar/reabrir | `Test-RestartLoop.ps1 -Iterations 20` | Sí |
| 5 | Reconexión | `Watch-Session.ps1` mientras apagas 5 s la Wi-Fi de la 3DS | Semiautomática |
| 6 | Memoria | Incluido en 1 y 3 | Sí |
| 7 | Cierres y ANR | Incluido en todos (logcat `crash` + `ANR in`) | Sí |
| 8 | Input Redirection | `Test-Input.ps1` (tú pulsas y confirmas) | Manual |
| 9 | Audio | `Run-Benchmark.ps1 -Audio true` + escuchar; el JSON indica "recibiendo/perdidos" | Semiautomática |
| 10 | Comparar versiones | `Compare-Results.ps1 <antes> <después>` (umbral 5 %, sale con 4 si algo empeora) | Sí |
| — | Micro-benchmarks JVM | `Run-JvmBenchmarks.ps1` | Sí (sin dispositivo) |

## 8. Ciclo de optimización

1. `Run-Benchmark.ps1 -Label base-<commit>` (×3) → línea base.
2. Elige un cuello de botella **medido** (p. ej. `incomplete_ratio` alto → red/calidad; `decode_p95_ms` → decodificador).
3. Cambia el código → `Build-Apk.ps1` → `Install-Apk.ps1`.
4. `Run-Benchmark.ps1 -Label exp-<idea>` (×3, mismo juego y escena) → `Compare-Results.ps1`.
5. Solo si mejora sin empeorar nada (o el empeoramiento está justificado): commit local + resultados en
   `docs/IMPLEMENTATION_PROGRESS.md`.

Mantén constante: juego y escena, distancia de la 3DS al router, banda del Chromecast, perfil y duración.
