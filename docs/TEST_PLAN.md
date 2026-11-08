# Plan de pruebas

Tres niveles, de más a menos automatizable. Ninguno sustituye a la validación física (`HARDWARE_VALIDATION.md`).

## 1. Unitarias (JVM, `:core`) — `./gradlew :core:test`

| Área | Fichero | Qué se comprueba |
|---|---|---|
| Control TCP | `NtrControlPacketTest` | 84 bytes LE, magic, args del 901 idénticos a NTRViewer-HR, acotación, ida y vuelta |
| Reensamblado JPEG | `JpegFrameAssemblerTest` | en orden, desordenado, duplicados, 1 paquete, múltiplo exacto, pérdida, pantallas intercaladas, >256 paquetes (wrap u8), paquete truncado, flags de cabecera, clasificador (vídeo/audio/lossless/RS/inválido), frame viejo no reaparece |
| Buzón | `FrameMailboxTest` | solo el último frame por pantalla, orden entre pantallas, reciclaje sin agotar buffers |
| Audio | `AudioSequencerTest` | redundancia, recuperación de 1 pérdida, huecos → silencio, wrap de secuencia, paquetes viejos |
| Downsample | `DownsampleTest` | reconstrucción even/odd |
| Input Redirection | `InputRedirectionTest` | neutro = valores de Luma3DS, cada botón activo-bajo, ZL/ZR, HOME/POWER, táctil (escala y límites), Circle Pad, C-Stick 45°, deadzone/sensibilidad, perfiles, swap, gatillos/HAT, HOME solo si se permite, acciones de app, cursor desactiva C-Stick, mando vacío = neutro, serialización |
| Layout | `LayoutTest` | proporción, 1080p, rotaciones, vertical/horizontal, intercambio, separación, escalado entero, transformación JPEG→pantalla (orientación), coordenadas táctiles inversas con todas las rotaciones, cursor se mueve en la dirección de la TV, límites |
| Ajustes | `SettingsTest` | valores por defecto, ida y vuelta completa, valores corruptos, validación de IP/subred |

## 2. Integración por red real (loopback, `:tools:mock-3ds-sender`) — `./gradlew :tools:mock-3ds-sender:test`

`SessionIntegrationTest` levanta `FakeNtrConsole` (servidor TCP + emisor UDP + receptor 4950) y la `NtrSession` real:

- conexión activa, ambas pantallas, orientación verificada por píxeles de color en las esquinas;
- reconexión tras corte del stream (simula caída de Wi-Fi / NTR-HR detenido);
- pérdida de paquetes del 3 %;
- modo pasivo y aprendizaje de la IP;
- audio DSP sintético;
- stream no soportado (Reliable Stream) → error explicativo;
- consola inalcanzable → error tras N reintentos;
- TCP correcto pero sin UDP → diagnóstico "no llega vídeo UDP";
- cambio de calidad/prioridad en caliente;
- puerto UDP liberado entre sesiones (desconectar/reconectar 3 veces);
- Input Redirection: paquetes de 20 bytes, keep-alive y neutro al parar.

### 2b. Robustez (`SessionRobustnessTest`, misma consola simulada con inyección de fallos)

Respuestas TCP fragmentadas byte a byte, magic incorrecto, `dataLen` absurdo (sin asignar memoria), cierre del TCP
tras el 901 con vídeo continuo, primer 901 ignorado (reenvío), datagramas sin ningún frame completo (nunca STREAMING),
paquetes duplicados, vídeo desde otra IP (127.0.0.3) diagnosticado, modo pasivo no secuestrable por vídeo incompleto
ajeno, puerto UDP ocupado, `stop()` rápido durante un `connect()`, IP inválida.

### 2c. Fixtures y soak (`:core`)

`LumaFixturesTest`: datagramas de 20 bytes escritos a mano en hexadecimal a partir de `input_redirection.c`, POWER
imposible, HOME desactivado por defecto, elección de ejes por dispositivo. `ConnectionChecklistTest`: etapas.
`PipelineSoakTest`: 20 000 frames con 2 % de pérdida, desorden y duplicados; todo frame completado se consume o se
cuenta como descartado, buffers no se agotan; imprime el rendimiento (no es una medida del Chromecast).

## 3. Emulador Android TV (CI) — `./gradlew :app:connectedDebugAndroidTest`

`StreamEndToEndTest` (job "emulator" de GitHub Actions, Android TV API 31 x86): consola simulada dentro del proceso
de test → app real → `BitmapFactory` → `SurfaceView`. Verifica estado STREAMING, >30 frames decodificados y
presentados sin errores, comando 901 correcto, llegada de paquetes neutros de Input Redirection a 127.0.0.1:4950, salida a
otra pantalla (espera real a `onStop`) y reanudación de la imagen. `appIsAnAndroidTvLauncherAppWithBanner`: intent
LEANBACK_LAUNCHER, banner y pantalla táctil no requerida.

Tests JVM de la app (`GamepadKeyMappingTest`): códigos de tecla Android → controles; teclas de sistema ignoradas.

## 4. Manual con `mock-3ds-sender` (sin 3DS)

```bash
./gradlew :tools:mock-3ds-sender:installDist
tools/mock-3ds-sender/build/install/mock-3ds-sender/bin/mock-3ds-sender            # simula NTR-HR en el PC
# En 3DS2TV pon como "IP de la 3DS" la IP del PC y pulsa Conectar.
tools/mock-3ds-sender/build/install/mock-3ds-sender/bin/mock-3ds-sender --loss 0.05 --reorder   # mala Wi-Fi
tools/mock-3ds-sender/build/install/mock-3ds-sender/bin/mock-3ds-sender --push IP_CHROMECAST --audio  # modo pasivo
```
El patrón tiene cuadrados rojo (arriba-izquierda), azul (arriba-derecha) y verde (abajo-izquierda) de la pantalla
nativa: así se comprueba la orientación y las rotaciones a simple vista. La consola de este simulador imprime los
botones recibidos por Input Redirection, útil para probar mapeos y el cursor táctil con un mando real sin 3DS.

## Criterios

- Todos los tests automáticos en verde en CI antes de etiquetar una versión.
- Ninguna función se anuncia como "probada en hardware" sin una fila completa en `HARDWARE_VALIDATION.md`.
