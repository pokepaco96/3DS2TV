# Progreso de implementación (para continuar en otra sesión)

## Punto de partida
- Rama de trabajo: `claude/ultra-low-latency`, creada desde `db91d9e` (= `origin/claude/new-session-8fkcky`, que incluye el PR #1 "renderer-no-vsync-block" del usuario).
- **Commits solo locales**: no se ha hecho push (el usuario debe autorizarlo).

## Evidencia de hardware real disponible (9-nov-2026, informada por el usuario)
Chromecast con Google TV 4K, Android 14 · New 3DS XL con NTR-HR. Decodificación 4,4–6,7 ms; recepción→pantalla 5,9–7,5 ms;
≈10 % de frames incompletos (pérdidas Wi-Fi de la 3DS); 11,8 FPS con un juego de GBA (límite de NTR-HR); audio funcionando tras `7dbc806`.

## Hallazgos y correcciones de esta fase
| # | Hallazgo (confirmado con test o lectura de código) | Corrección | Prueba |
|---|---|---|---|
| P1 | Frame más antiguo mostrado tras uno nuevo (`[9, 8]`) | `JpegFrameAssembler`: último id por pantalla, u8, reinicio por silencio/racha | `wholeOlderFrameArrivingAfterNewerIsNotShown`, `frameIdWrapAroundIsNewer`, `consoleRestartIsAcceptedAfterSilence`, `counterResetMidStreamRecoversWithinThreeFrames`, `qualityChangeWithRealisticFrameIdResetKeepsStreaming` |
| P2 | Estadísticas finales leídas tras liberar la sesión | Captura previa en `stopSession`, tasas congeladas | Revisión de código (Android) |
| P3 | Cabecera JPEG corrupta → reserva enorme en el fallback | `JpegHeader` antes de decodificar | `JpegHeaderTest` (incl. fuzz 20 000) |
| P4 | Escalado entero no entero / medio píxel | `ScreenLayout.compute` en píxeles | `integerScaleIsIntegerPerScreenWithCustomSizes`, `allLayoutsStartOnWholePixels` |
| P5 | Carrera `pendingRequest` (pérdida de un ajuste) | `AtomicReference.getAndSet` | `concurrentSettingsUpdatesAreNeverLost` |
| P6 | `join()` en hilo principal / callbacks de sesiones viejas | `stop(join=false)`, generación de sesión | `nonBlockingStopReturnsImmediatelyAndFreesThePort` |
| P7 | Lote de eventos de joystick por vsync | `requestUnbufferedDispatch(SOURCE_CLASS_JOYSTICK)` | Lint; validación con cámara pendiente |
| P8 | Audio: escrituras parciales / `ERROR_DEAD_OBJECT` | Contadores y recreación de la pista | Revisión de código; prueba física pendiente |
| P9 | 10 % de frames perdidos en Wi-Fi real | Perfiles + `AdaptiveQuality` | `AdaptiveQualityTest` |
| P10 | Render asignaba matrices por frame | Caché por pantalla | Benchmark `placement.jpegToSurface` (coste ya mínimo) |

## Benchmarks
`docs/OPTIMIZATION.md` (antes `db91d9e` / después). Comando: `./gradlew :core:test -Pbench --tests '*bench*'`.

## Tests (última ejecución local)
- `:core:test` 86 funcionales + 6 benchmarks (saltados sin `-Pbench`), 0 fallos.
- `:tools:mock-3ds-sender:test` 29, 0 fallos, 3 ejecuciones seguidas estables.
- `:app:testDebugUnitTest` 2, 0 fallos · `:app:lintDebug` sin incidencias · `:app:assembleDebug` OK.
- Test instrumentado (emulador Android TV): **no ejecutado en esta fase** (no hay KVM local; CI solo corre tras push).

## Laboratorio físico (preparado, sin ejecutar en hardware)
- `tools/hardware-lab/` (PowerShell) + `docs/HARDWARE_LAB.md`; config privada `lab.config.json` ignorada por Git.
- App (solo debug): `LabControlReceiver` (START/STOP/EXPORT_METRICS, permiso DUMP) y `lab_autostart`; ausente del release.
- Scripts **no ejecutados** aquí (sin PowerShell permitido ni dispositivos): solo comprobación estática de sintaxis.
- Siguiente paso en casa: `Check-Requirements` → `Connect-Chromecast -SaveSerial` → `Build-Apk` → `Install-Apk` →
  `Run-Benchmark -Label base` ×3 y anotar aquí los resultados (commit, APK SHA-256, perfil, juego).

## Pendiente (bloqueos reales)
1. Push de la rama y CI (requiere autorización del usuario) → APK para probar.
2. Hardware: medir con cámara la latencia total y el efecto de `requestUnbufferedDispatch`; comprobar perfiles y calidad adaptativa en Wi-Fi real; 30–60 min de sesión; audio con cambio de salida HDMI.
3. Reliable Stream (KCP+FEC de NTR-HR) si con perfiles y calidad adaptativa siguen los tirones (≥5 % de frames incompletos con un juego de 3DS).

## Commits de esta fase
Ver `git log db91d9e..HEAD`.
