# Auditoría de continuación (8-nov-2026)

Punto de partida: rama `claude/new-session-8fkcky`, commit `db47cd6` (árbol limpio, igual que `origin`).
Rutas y líneas de los hallazgos se refieren a **`db47cd6`**; la corrección está en los commits citados.

## 1. Línea base reproducible (clon limpio de `db47cd6`, entorno cloud, JDK 21, Android SDK 35, sin KVM)

| Comando | Resultado real |
|---|---|
| `./gradlew --no-daemon :core:test` | exit 0 (43 s) · 51 tests, 0 fallos |
| `./gradlew --no-daemon :tools:mock-3ds-sender:test` | exit 0 (30 s) · 11 tests, 0 fallos |
| `./gradlew --no-daemon :app:testDebugUnitTest` | exit 0 (23 s) · **0 tests** (el módulo app no tenía tests JVM) |
| `./gradlew --no-daemon :app:lintDebug` | exit 0 · 1 aviso (`DataExtractionRules`, `allowBackup`) |
| `./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest` | exit 0 · APK SHA-256 `9f291e6f70d36ef858ae27058f0a9ae6d49541c9994d23f54f8f69fd5dd3e24b` |
| `./gradlew --no-daemon :tools:mock-3ds-sender:distZip` | exit 0 · `mock-3ds-sender.zip` |
| `connectedDebugAndroidTest` | No ejecutable aquí (sin `/dev/kvm`); se ejecuta en GitHub Actions |

## 2. Fuentes upstream contrastadas (sin cambios desde la primera auditoría)

| Proyecto | Commit | Ficheros/símbolos revisados |
|---|---|---|
| https://github.com/xzn/ntr-hr | `cbc8858` (tag `v0.3.8.2`) | `source/ns.c` (`NS_PACKET`, `nsHandleRemotePlay`, `nsMainLoop`), `source/menu/rp_menu.c` (`rpUpdateParamsFromMenu`, `rpClampParamsInMenu`: `dstAddr = 0`, `tryInitRemotePlay`), `include/constants.h` (`PACKET_SIZE 1448`, `RP_DATA_SIZE`, `RP_DST_PORT_DEFAULT`, flags), `nwm_rs/.../thread_nwm/vars.rs` (`DataHdr::init`, `get_packet_data_size_const`), `nwm_rs/.../start_up/safe_impl.rs` (aprendizaje de la IP del viewer), `nwm_rs/.../handle_port/safe_impl.rs` (`SVC_NWM_CMD_PARAMS_UPDATE` → `set_reset_threads`), `nwm_rs/.../thread_audio.rs`, `nwm_rs/.../thread_main.rs` |
| https://github.com/xzn/ntrviewer-hr | `556c6d0` | `ntr_hb.c`, `ntr_rp.c` (`handle_recv`, `test_kcp_magic`, `socket_action`, `screen_process`), `ntr_audio.h`, `ui_input_redirection.c`, `ui_renderer_ogl.c` (`vs_data_str`), `const.h` |
| https://github.com/LumaTeam/Luma3DS | `1441535` | `sysmodules/rosalina/source/input_redirection.c` (`hidData`, `irData`, `inputRedirectionThreadMain`), `include/menu.h` (`DEFAULT_MENU_COMBO`), `source/menus/miscellaneous.c` |

Confirmado de nuevo en código: cabecera TCP de 84 bytes LE, magic `0x12345678`, cmd 901 con magic `1404036572`,
args[4] = puerto | flags (sin flags = JPEG/UDP), datos de 1444 bytes por paquete no final (`PACKET_SIZE − DATA_HDR_SIZE`),
hdr[1] bit0 = superior, bit4 = fin; hdr[2] = `2 | ds<<2 | lossless`; audio hdr[2] = 4 con 2 frames de 640 bytes;
Input Redirection UDP 4950, 12 o 20 bytes, palabras LE `[hid activo-bajo, touch, circle, ir, especiales]`.
Nuevo dato upstream relevante: un 901 recibido durante el streaming llega a nwm como `PARAMS_UPDATE` y **reinicia los
hilos de NTR-HR** si la configuración cambia (por eso activar/desactivar audio en caliente es posible).

## 3. Hallazgos

| # | Sev. | Ruta:línea (`db47cd6`) | Evidencia | Riesgo | Corrección | Prueba |
|---|---|---|---|---|---|---|
| 1 | **ALTO** | `core/.../session/NtrSession.kt:134-135`, `:325` | `stop()` hacía `join(1000)` + `join(1500)` y no cerraba el socket de un `connect()` en curso (timeout 2,5 s). `stop()` se llama en `StreamActivity.onStop` (hilo principal). | Bloqueo de la UI ~2,5 s al salir mientras reconecta; con el resto de `stop()` riesgo de ANR. | `stop()` cierra el socket UDP **y el TCP en curso**; joins de 500 ms (`57f6c1c`). | `SessionRobustnessTest.stopIsFastEvenWhileConnecting` (< 1,2 s) |
| 2 | **ALTO** | `core/.../session/NtrSession.kt:186-190` | En modo pasivo sin IP, la sesión se fijaba a la IP del **primer datagrama** con cabecera de vídeo, completo o no. | Un datagrama ajeno en la LAN secuestra la sesión; la consola real queda ignorada. | Candidato por origen y bloqueo solo tras un **frame JPEG completo**; candidato caduca a los 2 s (`57f6c1c`). | `passiveModeIsNotHijackedByIncompleteStrayVideo` |
| 3 | **ALTO** (usabilidad) | `NtrSession.kt:180-183` | Vídeo desde otra IP distinta de la configurada se descartaba en silencio (`foreignPackets++`). | El usuario ve "no llega vídeo" sin saber que tecleó otra IP. | Se registra el origen ajeno; mensaje de error y checklist lo nombran (`57f6c1c`). | `videoFromAnotherHostIsIgnoredAndDiagnosed` |
| 4 | **ALTO** (requisito) | `StreamActivity.kt` (estado) | La UI solo mostraba un estado global; no distinguía TCP / 901 / UDP / frame completo / imagen presentada / Input Redirection. | Diagnóstico de la primera prueba física a ciegas. | `SessionDiagnostics` + `ConnectionChecklist` en panel de estado, HUD y prueba de conexión (`57f6c1c`, `9c59336`). | `ConnectionChecklistTest`, `datagramsWithoutCompleteFramesNeverCountAsStreaming` |
| 5 | MEDIO | `NtrSession.kt:99` | `reuseAddress = true` en el `DatagramSocket` UDP. | En Linux/Android permite que otro socket comparta el puerto 8001 y reciba parte de los paquetes. | Bind sin `SO_REUSEADDR`; error explícito "¿lo usa otra app?" (`57f6c1c`). | `udpPortAlreadyInUseGivesClearError` |
| 6 | MEDIO | `NtrSession.kt:359` | `dataLen` (u32) aceptado hasta 1 MiB y **asignado** por respuesta; >1 MiB cerraba sin diagnóstico; magic incorrecto sin contador. | Memoria por respuesta controlada por la red; fallos silenciosos. | Lectura por bloques de 16 KiB con descarte, límite 4 MiB tratado como u32, contador `tcpProtocolErrors` (`57f6c1c`). | `hugeDataLenIsRejectedWithoutAllocating`, `badMagicReplyIsReportedAndVideoContinues`, `tcpRepliesFragmentedByteByByteAreParsed` |
| 7 | MEDIO | `app/.../net/NetworkInfo.kt:14,23` | Usaba la primera IPv4 de `activeNetwork`; con una VPN activa es la del túnel. | Se indicaría al usuario una "Viewer IP" inalcanzable para la 3DS. | Enumera redes, excluye `TRANSPORT_VPN`, solo Wi-Fi/Ethernet, prioriza IP privada (`9c59336`). | Revisión de código (necesita dispositivo con VPN para prueba física) |
| 8 | MEDIO | `app/.../input/GamepadTracker.kt:81-83` | Stick derecho fijo en `AXIS_Z/RZ`. Mandos HID genéricos usan `AXIS_RX/RY` (y Z/RZ como gatillos). | C-Stick muerto o gatillos moviendo el C-Stick en mandos genéricos. | `AxisLayout` elegido por los rangos que declara el dispositivo; gatillos normalizados por su rango real (`34eec64`). | `LumaFixturesTest.standardPadUsesZRz`, `genericHidUsesRxRyAndZAsTriggers`, `triggerNormalization` |
| 9 | MEDIO | `StreamActivity.kt:523` | El cursor táctil solo se bloqueaba al activarlo; si después se cambiaba a "Solo superior" seguía tocando en una pantalla invisible. | Toques no visibles en la 3DS. | Se desactiva al aplicar un layout sin pantalla inferior (`34eec64`). | Revisión de código + `HARDWARE_VALIDATION` #10 |
| 10 | MEDIO | `app/src/androidTest/.../StreamEndToEndTest.kt:196` | `waitFor { state == DISCONNECTED \|\| !running }` era siempre cierto (`running` es true). Además `Thread.sleep(300)` y `Thread.sleep(1500)`. | Falso positivo: el test no comprobaba el cierre. | Espera real a `DISCONNECTED` tras `onStop`, sin sleeps; nuevo test de launcher/banner (`9c59336`). | El propio test en CI |
| 11 | MEDIO | `app/` | 0 tests JVM en `:app`. | Mapeo de keycodes sin cobertura. | `GamepadKeyMappingTest` (`34eec64`). | — |
| 12 | BAJO | `StreamActivity.kt:625` | HUD: "N paquetes" de Input Redirection. | Podía interpretarse como recibidos/aplicados (el protocolo no tiene ACK). | "N enviados (UDP sin confirmación)"; checklist lo explica (`9c59336`). | `ConnectionChecklistTest.passiveModeAndInputRedirection` |
| 13 | BAJO | `README.md:12` | "tests JVM (64)": eran 51. | Documentación inexacta. | README regenerado con cifras medidas. | — |
| 14 | BAJO | `AndroidManifest.xml:17` | Aviso lint `DataExtractionRules`. | Ninguno funcional. | Reglas de backup/extracción que excluyen todo (lint: *No issues found*). | lint |
| 15 | BAJO (documentado) | `JpegFrameAssembler.kt` | Desenrollado del índice u8 igual que ntrviewer-hr (`packet + 128 < max`): con frames > 256 paquetes **muy** desordenados (>128 de distancia) un paquete se interpretaría mal y el frame se perdería. | Solo a calidad extrema (> 370 KB por JPEG); el frame se descarta, no se corrompe. | Se mantiene (mismo comportamiento que el viewer de referencia); documentado. | `largeFrameWithIndexWrapAround` |
| 16 | BAJO (riesgo externo) | upstream `ns.c::nsMainLoop` | El puerto 8000 de NTR atiende **una** conexión con `recv` bloqueante (menú sin `nsPoll2`). Si el Chromecast desaparece sin cerrar TCP (corte de Wi-Fi), NTR puede seguir esperando en ese socket. | Tras un corte brusco la reconexión TCP podría no ser atendida hasta reiniciar Remote Play. | 3DS2TV cierra siempre el TCP de forma ordenada; documentado en la guía de primera prueba con alternativa (modo pasivo / reiniciar Remote Play). | Validación física (fase D) |
| 17 | INFO | — | Revisado sin defecto: `FrameMailbox` (1 pendiente por pantalla, sin crecimiento), `ScreenBuffers` (el decodificador nunca escribe el bitmap visible; `surfaceDestroyed` espera al render), `BitmapFactory.inBitmap` con `IllegalArgumentException` y verificación de dimensiones, envío de Input Redirection fuera del hilo principal (corregido en `4073863`), HOME desactivado por defecto, POWER imposible. | — | — | `PipelineSoakTest` (20 000 frames), `LumaFixturesTest.powerIsNeverProducedByAnyMapping` |

## 4. Lo que no puede validarse sin hardware

Compatibilidad real con NTR-HR en una New 3DS XL, rendimiento del Chromecast (decodificación, temperatura, 30 min),
Bluetooth/USB reales, efecto de Input Redirection en Rosalina, audio DSP real, comportamiento de la IP con VPN,
recuperación tras corte de Wi-Fi real. Procedimiento: `FIRST_REAL_DEVICE_TEST.md`; registro: `HARDWARE_VALIDATION.md`.

## 5. Decisiones mantenidas

- Arquitectura Kotlin/JVM + `BitmapFactory` + `SurfaceView`: sin defecto que justifique NDK/SDL.
- Reliable Stream/Delta/Lossless: **no se implementan** hasta que la primera prueba real demuestre que JPEG Compat no es
  utilizable en tu red (criterio del plan). Si se necesita: portar `ikcp.c` modificado + FEC "fecal" de ntrviewer-hr
  (MIT/BSD, revisar licencias de cada fichero) manteniendo JPEG Compat como ruta estable.
- Licencia MIT: los únicos fragmentos derivados siguen siendo de ntrviewer-hr (MIT), sin código GPL.
