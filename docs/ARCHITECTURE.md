# Arquitectura de 3DS2TV

```
 New 3DS XL (Luma3DS + NTR-HR)                         Chromecast con Google TV (APK 3DS2TV)
 ┌────────────────────────────┐   TCP 8000 (cmd 901,   ┌────────────────────────────────────────────┐
 │ NTR-HR  menú/ns.c          │◀── heartbeat) ─────────│ NtrSession (control thread)                │
 │ NTR-HR  nwm (encoder JPEG) │── UDP 8001 vídeo/audio▶│ NtrSession (udp thread, prio. máxima)      │
 │ Rosalina InputRedirection  │◀── UDP 4950 (20 B) ────│ InputRedirectionSender (60 Hz + al cambio) │
 └────────────────────────────┘                        └────────────────────────────────────────────┘
```

## Módulos

| Módulo | Tipo | Contenido | Tests |
|---|---|---|---|
| `:core` | Kotlin/JVM puro (sin Android) | `ntr/` protocolo (paquetes TCP, clasificador, reensamblador JPEG, buzón de frames, audio, downsample), `session/` máquina de estados y reconexión, `input/` Input Redirection + mapeos + sticks, `layout/` composición/rotación/cursor táctil, `settings/` modelo persistente | `core/src/test` (unitarios) |
| `:tools:mock-3ds-sender` | JVM, app de consola | `FakeNtrConsole`: servidor TCP NTR, vídeo JPEG con patrón de prueba, audio senoidal, receptor de Input Redirection, inyección de pérdidas/reordenación/cortes | `src/test`: integración real por loopback |
| `:app` | Android TV | Actividades, decodificador, renderizador, audio, captura de mandos | `src/androidTest`: end-to-end en emulador |

## Flujo de datos (vídeo)

1. **UDP thread** (`NtrSession.udpLoop`): un único `DatagramSocket` (buffer de 2 MB) abierto durante toda la sesión,
   filtra por IP de la consola, `PacketClassifier` separa vídeo JPEG / audio / RS / lossless.
2. **`JpegFrameAssembler`**: hasta 4 frames en vuelo con buffers preasignados; desenrolla el índice u8; un frame se
   entrega cuando están todos sus paquetes; al completar un frame se descartan los incompletos más antiguos de la misma
   pantalla (nunca se muestra un frame viejo después de uno nuevo).
3. **`FrameMailbox`**: como máximo **un frame pendiente por pantalla**. Si el decodificador va atrasado, el nuevo frame
   sustituye al pendiente (contado como "descartado"). Latencia acotada, sin colas que crezcan.
4. **`FrameDecoder`** (hilo propio): `BitmapFactory.decodeByteArray` con `inBitmap` → sin asignaciones por frame.
5. **`ScreenBuffers`**: doble buffer por pantalla; el decodificador escribe en *back*, publica con un swap bajo lock.
6. **`StreamRenderer`** (hilo propio): dibuja solo cuando hay algo nuevo, con `lockHardwareCanvas` (GPU) y una matriz
   por pantalla = rotación del framebuffer + rotación del usuario + escala + traslación (`Placement.jpegToSurface`).
   `Paint.isFilterBitmap` = nearest (píxel) o bilineal.

Métricas (`StreamStats`): FPS recibidos por pantalla, FPS mostrados (presentaciones con al menos un frame nuevo),
bitrate, frames incompletos, descartados, errores de decodificación, tiempo de decodificación y tiempo
recepción→presentación en el Chromecast (no incluye el tiempo en la consola ni en el aire: no se puede medir sin
reloj compartido, así que no se muestra una "latencia total").

## Sesión y reconexión (`NtrSession`)

```
DISCONNECTED → CONNECTING → CONNECTED (901 enviado) → STREAMING
                    │              │ sin vídeo 3 s → reenvía 901; 8 s → NO_VIDEO → backoff
                    │              └ RS/Lossless detectado → ERROR (mensaje)
                    └ TCP falla → RECONNECTING (1, 2, 4, 8 s…) → ERROR tras N intentos
STREAMING → (3 s sin vídeo) → RECONNECTING → CONNECTING …
Modo pasivo: SEARCHING ⇄ STREAMING (sin TCP; aprende la IP de la consola del primer paquete)
```
Si el canal TCP cae pero el vídeo sigue llegando (p. ej. parche NFC de NTR-HR), la sesión continúa.
`updateRequest()` reenvía el 901 en caliente al cambiar calidad/QoS/prioridad/audio (NTR-HR lo admite durante el streaming).

## Entrada

`GamepadTracker` (Android) → `PadSnapshot` (independiente de Android) → `InputMapper` (mapa + deadzone +
sensibilidad + cursor) → `InputFrame` → `InputRedirectionSender`.

- Un único mando activo (el preferido o el primero que pulse algo); los demás se ignoran.
- Al desconectarse el mando activo, abrir el menú, pausar la actividad o salir: estado neutro inmediato.
- Acciones de la app por flanco: abrir menú (L3 por defecto, Select+Start 1 s, BACK del mando de TV) y cursor táctil (R3).
- Cursor táctil: `TouchCursor` guarda la posición en coordenadas **nativas** de la pantalla inferior (320×240); el
  movimiento del stick se interpreta en direcciones de la TV y se convierte con la inversa de la rotación, de modo que
  las coordenadas enviadas son correctas con cualquier rotación/escala/posición.

## Audio

Paquetes tipo 4 → `AudioSequencer` (elimina redundancia, cuenta pérdidas, inserta silencio acotado) → `AudioPlayer`
(`AudioTrack` 32728 Hz estéreo, modo baja latencia en API 26+, escrituras no bloqueantes, descarte si la cola supera
120 ms). El hilo de red nunca se bloquea por el audio.

## Persistencia

`AppSettings` (core) serializa a clave/valor; en Android se guarda en `SharedPreferences` (`settings`). Valores
corruptos vuelven al valor por defecto o se acotan (probado).

## Decisiones

- Sin NDK: JPEG lo decodifica libjpeg-turbo del sistema vía Skia; medir en hardware antes de optimizar más.
- Sin AndroidX en la app (solo en tests instrumentados) ni servicios de Google: APK ~1 MB, 100 % local.
- Sin Google Cast: el Chromecast ejecuta la app receptora.
