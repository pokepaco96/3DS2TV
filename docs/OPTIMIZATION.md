# Optimización de latencia — decisiones y mediciones

> 3DS2TV está pensado **solo para una red local privada y de confianza** (3DS → Wi-Fi de casa → Chromecast).
> No lo expongas a Internet ni abras puertos en el router. Las validaciones que se conservan existen para
> que datos corruptos no tumben la app, no para resistir ataques.

## Cadena de latencia (de pulsar un botón a ver el resultado)

```
mando ─BT─▶ Android input ─▶ 3DS2TV (mapeo + UDP 4950) ─Wi-Fi─▶ Rosalina ─▶ juego (frame N)
juego ─▶ NTR-HR captura + JPEG (3 núcleos) ─Wi-Fi 2,4 GHz─▶ UDP 8001 ─▶ ensamblado ─▶ decodificación ─▶ render ─▶ HDMI ─▶ TV
```

| Tramo | Quién lo controla | Medido |
|---|---|---|
| Lote de eventos de joystick de Android (hasta 1 vsync ≈ 16 ms) | 3DS2TV: `requestUnbufferedDispatch(SOURCE_CLASS_JOYSTICK)` | Reducción teórica; pendiente de cámara |
| Evento → datagrama enviado | 3DS2TV | JVM: p50 0,12 ms · p95 0,21 ms (`inputEventToSendLatencyIsMeasuredAndSmall`). En el HUD del Chromecast: "Mando→envío" |
| Wi-Fi 3DS (2,4 GHz) | Red / router | Frames incompletos en el HUD (≈10 % en la primera prueba real) |
| Codificación JPEG en la 3DS | NTR-HR (calidad, QoS) | FPS recibidos del HUD |
| Ensamblado UDP | 3DS2TV | JVM: 1,8 µs por frame de 21 paquetes |
| Decodificación JPEG | Android `BitmapFactory` | Chromecast: ~4,4–6,7 ms (pruebas reales) |
| Recepción → presentación | 3DS2TV + SurfaceFlinger | Chromecast: 5,9–7,5 ms (pruebas reales) |
| Salida HDMI + procesado del televisor | TV ("modo juego") | Solo con cámara |

**Conclusión de las mediciones:** el código de red/ensamblado de 3DS2TV cuesta ≈3 µs por frame; el tiempo
está en la decodificación (≈6 ms), en las pérdidas Wi-Fi de la 3DS y en la propia consola/TV. Por eso
las mejoras se centran en: no mostrar/decodificar trabajo inútil, no añadir esperas, reducir pérdidas
(perfiles + calidad adaptativa) y quitar el lote de eventos de los sticks.

## Decisiones (con evidencia)

| Tema | Decisión | Por qué |
|---|---|---|
| Orden de frames | Por pantalla se recuerda el último `frame_id` mostrado (u8, módulo 256); un frame más antiguo se descarta sin decodificar. Reinicio del contador aceptado tras 3 ids crecientes o 300 ms sin frames | Bug reproducido (`[9, 8]`). NTR-HR usa `CURRENT_FRAME_IDS` por pantalla y lo pone a 0 al reiniciar el codificador (`work_thread/vars.rs:61,84,320`), lo que ocurre en cada cambio de calidad |
| Validación JPEG | **B: lector mínimo de cabecera SOF** antes de decodificar (22–33 ns/frame, sin asignaciones, fuzzing de 20 000 entradas) | A (decodificar y comparar después) gastaba una decodificación completa en frames inválidos y su ruta de reserva sin `inBitmap` podía reservar una imagen gigante con una cabecera corrupta. C (`inJustDecodeBounds`) es una segunda pasada de libjpeg por frame. B cuesta ~1/50 000 de una decodificación (JVM: 1,2–1,4 ms) |
| Búfer UDP del kernel | 384 KiB (antes 2 MiB) | 2 MiB ≈ 1 s de vídeo a 16 Mbit/s acumulable si el hilo se retrasa; 384 KiB ≈ unos pocos frames. Android no expone los descartes del kernel por socket, así que no se puede medir el backlog directamente |
| Copia del buzón (`FrameMailbox`) | Se mantiene (1 µs por frame de 30 KB) | Eliminarla exige ceder la propiedad de los slots del ensamblador al decodificador (más sincronización) para ahorrar ≈0,01 % de CPU a 120 FPS |
| Render | Ya desacoplado del vsync (PR #1): el decodificador nunca espera al render. Matriz por pantalla cacheada (sin asignaciones por frame) | — |
| `DatagramChannel` | No adoptado | `DatagramPacket` reutilizado ya evita copias; el receptor no es un cuello de botella medido |
| NDK/libjpeg-turbo propio | No adoptado | `BitmapFactory` ya usa libjpeg-turbo (Skia); sin perfil en el dispositivo que lo justifique |
| Escalado entero | Factor entero por pantalla (incluido su tamaño personalizado) y orígenes en píxeles enteros | Con orígenes en medio píxel el filtro nearest producía columnas de distinto ancho |
| Envío de mandos | Inmediato al cambiar + repetición cada 16 ms | Rosalina conserva el último estado; repetir protege de pérdidas UDP. 20 B × 60/s ≈ 1,2 KB/s |
| Cierre de sesión | Sin `join()` en el hilo principal; identificador de generación ignora callbacks tardíos | Antes podía bloquear la UI hasta ~1,5 s |
| Audio | Cola máxima por perfil (60/100/150 ms), escrituras no bloqueantes, `ERROR_DEAD_OBJECT` recrea la pista | El hilo de red nunca espera al audio; un cambio de salida HDMI ya no deja la app muda |

## Perfiles

| Perfil | JPEG | QoS | Audio máx. | Uso |
|---|---|---|---|---|
| Ultra baja latencia (por defecto en instalaciones nuevas) | 70 | 10 Mbps | 60 ms | Jugar |
| Equilibrado | 80 | 12 Mbps | 100 ms | — |
| Calidad | 90 | 16 Mbps | 150 ms | Juegos lentos, buena Wi-Fi |

Un frame de N paquetes llega completo con probabilidad (1−p)^N: menos calidad ⇒ menos paquetes ⇒ menos
frames perdidos, y además la 3DS codifica más rápido. **Calidad adaptativa** (activable): −10 si más del
12 % de frames llega incompleto 2 s seguidos; +5 tras 8 s por debajo del 3 %; 3 s de enfriamiento; nunca
por encima de la calidad elegida ni por debajo de 50. Solo funciona con el canal TCP (es lo que envía la
calidad a NTR-HR). Cada cambio hace que NTR-HR reinicie su codificador (≈2 frames).

## Benchmarks reproducibles

```
./gradlew :core:test -Pbench --tests '*bench*'
```
JVM (contenedor de CI, misma carga antes/después; **no son tiempos del Chromecast**):

Antes (`db91d9e`):
```
input.map+encode                       p50      230.0 ns/op  p95      261.2  min      144.2
placement.jpegToSurface(2 screens)     p50       19.5 ns/op  p95       34.9  min       17.6
udp.classify(video)                    p50        6.9 ns/op  p95       11.6  min        6.2
mailbox.offer+take(30KB)               p50     1015.3 ns/op  p95     1090.3  min      986.6
assembler.frame(21 pkts)               p50     1933.0 ns/op  p95     2076.4  min     1636.0
```
Después:
```
input.map+encode                       p50      153.9 ns/op  p95      223.1  min      144.6
placement.jpegToSurface(2 screens)     p50       20.7 ns/op  p95       27.5  min       18.5
jpeg.header(SOF parse)                 p50       22.1 ns/op  p95       33.8  min       19.5
jpeg.full decode (JVM ImageIO, ref)    p50  1436734.1 ns/op  p95  1593497.3  min  1379498.2
udp.classify(video)                    p50        8.7 ns/op  p95       13.9  min        7.3
mailbox.offer+take(30KB)               p50     1000.1 ns/op  p95     1043.7  min      966.0
assembler.frame(21 pkts)               p50     1767.9 ns/op  p95     1830.3  min     1716.2
```

## Medir la latencia real (requiere hardware)

1. Cámara a 240 fps (móvil en cámara lenta) que vea a la vez la 3DS y la TV; TV en "modo juego".
2. **Vídeo:** en un juego, provoca un cambio brusco (abrir un menú en la 3DS). Cuenta los fotogramas de
   cámara entre el cambio en la pantalla de la 3DS y en la TV; × (1000/240) = ms.
3. **Mando → consola:** pulsa A en el mando y cuenta hasta que el juego reacciona en la pantalla de la 3DS.
4. **Mando → TV (total):** igual, pero hasta que se ve en la TV.
5. Repite 10 veces por perfil y anota mediana y peor caso en `HARDWARE_VALIDATION.md`.
