# Investigación y decisiones técnicas

Auditoría realizada el **8 de noviembre de 2026** sobre clones reales de los repositorios (no solo README).
Cada dato de protocolo que usa 3DS2TV está verificado en código fuente; abajo se citan ficheros concretos.

## 1. Proyectos evaluados

| Proyecto | Estado verificado (8-nov-2026) | Licencia (fichero real) | Qué aporta | Decisión |
|---|---|---|---|---|
| `SancioPanza88/ntr-viewer-tv` | **No accesible**: `git clone` pide credenciales y la API de GitHub responde que no existe o no es accesible. No se pudo inspeccionar ni compilar. | No verificable | — | **Descartado**: sin código auditable no se puede reutilizar ni confirmar licencia. |
| `xzn/ntrviewer-hr` (commit `556c6d0`, 13-oct-2026) | Viewer de escritorio C/SDL3 activo. Implementa JPEG Compat, Reliable Stream (KCP modificado), Delta, Lossless, audio DSP e Input Redirection. | MIT (`LICENSE`), terceros: kcp (MIT), nuklear, etc. | Referencia principal del protocolo actual | **Referencia**; se porta solo la reconstrucción even/odd (`screen_process`, MIT, atribuido). |
| `xzn/ntr-hr` (commit `cbc8858`, tag `v0.3.8.2`, 13-oct-2026) | Emisor en la consola (C + Rust). Audio DSP presente desde la serie 0.3.8. | GPLv2 (`LICENSE`) | Contrato real del lado consola | **Solo referencia técnica** (no se copia código GPL). |
| `LumaTeam/Luma3DS` (commit `1441535`, 3-nov-2026) | `sysmodules/rosalina/source/input_redirection.c` | GPLv3 | Contrato de Input Redirection | **Solo referencia técnica**. |
| `Eiim/Chokistream` (commit `c350ee8`) | Cliente Java NTR/HzMod | GPLv2 | Contraste de orientación (`ImageManipulator.adjustStandard`) y cabecera UDP | Referencia, sin copiar código. |
| `samaBR85/3DSnickerStream` (commit `a590f54`, jul-2026) | Cliente C#/Avalonia con todos los formatos NTR-HR | GPLv3 | Segunda implementación de RS/Lossless para el futuro | Referencia, sin copiar código. |
| `RattletraPM/Snickerstream` | Cliente clásico (histórico) | — | Contexto histórico | No necesario. |
| `JambonBeurreMan/InputRedirectionClient-Qt-Android` (2018) | Cliente Qt para Android | MIT | Confirma little-endian (`qToLittleEndian`), ZR=bit1, ZL=bit2 en el byte IR | Referencia. Dependencias Qt antiguas: no integrable. |
| `paulhbarker/input-redirection` (2018) | Cliente Node | MIT (package.json) | Referencia adicional | Referencia. |
| `jojo8356/InputRedirectionClient` (2 commits, feb-2026) | Cliente Rust; sin fichero LICENSE (solo "based on TuxSH") | No confirmada | Referencia de mapeo; sondea con temporizador de 50 ms (20 Hz) | No se copia nada. 3DS2TV envía al instante + keep-alive a 60 Hz. |

## 2. Base elegida

**Aplicación Android TV nativa nueva** (Kotlin, sin NDK), con el protocolo reimplementado de forma independiente.

Motivos:
- El único port Android TV candidato no es accesible: no hay base que auditar.
- NTRViewer-HR es C + SDL3 + varios renderizadores de escritorio; portarlo entero al Chromecast exigiría NDK, SDL3 y adaptar
  su UI (Nuklear, ratón). El modo JPEG Compat cabe en pocos cientos de líneas de Kotlin y Android ya decodifica JPEG
  con libjpeg-turbo (Skia) a través de `BitmapFactory`, sin dependencias.
- Así el núcleo de protocolo es JVM puro y testeable sin dispositivo (módulo `:core`).

### Código reutilizado vs. reimplementado

| Pieza | Origen | Tratamiento |
|---|---|---|
| Reconstrucción "even/odd downsample" | `ntrviewer-hr/ntr_rp.c::screen_process` (MIT) | Portada a Kotlin (`core/.../ntr/Downsample.kt`), atribuida en `THIRD_PARTY_NOTICES.md`. |
| Fórmula de rotación 45° del C-Stick y escala del Circle Pad (0x5D0) | `ntrviewer-hr/ui_input_redirection.c` (MIT) | Reimplementado (constantes/ecuación), atribuido. |
| Resto (sesión, reensamblado, mailbox, layout, UI, mandos, audio) | — | Escrito desde cero. |
| NTR-HR, Luma3DS, Chokistream, 3DSnickerStream (GPL) | — | **Nada copiado**; solo hechos de protocolo (puertos, offsets, máscaras). |

Licencia de 3DS2TV: **MIT** (compatible con el fragmento MIT reutilizado; no incorpora código GPL).

## 3. Protocolo de vídeo confirmado (NTR / NTR-HR "JPEG Compat")

| Elemento | Valor | Fuente |
|---|---|---|
| Control | TCP **8000** en la consola | `ntr-hr/include/constants.h` `NS_MENU_LISTEN_PORT`; `ntrviewer-hr/main.c` |
| Cabecera TCP | 84 bytes, u32 little-endian: magic `0x12345678`, seq, type, cmd, args[16], dataLen | `ntr-hr/source/ns.c` `NS_PACKET`; `ntrviewer-hr/ntr_hb.c` |
| Heartbeat | cmd 0; la consola responde cabecera + texto de depuración | `ns.c::nsHandleDbgPrintPacket` |
| Iniciar/actualizar Remote Play | cmd **901**. args[0] = (prioridadSuperior<<8) \| factor; args[1] = calidad JPEG; args[2] = QoS en bytes/s (Mbps·128·1024); args[3] = magic `1404036572`; args[4] = puerto UDP del viewer \| flags (bit30 RS, bit31 Delta, bit29 Lossless); args[5] = audio (1 = DSP por UDP); args[6] = ancho completo | `ns.c::nsHandleRemotePlay`, `ntr_hb.c`, `constants.h` `RP_CONFIG_FLAG_*` |
| Dirección destino del vídeo | NTR-HR la aprende del paquete TCP que la consola envía desde su puerto 8000 al viewer (o la fija el usuario en el menú X+Y "Viewer IP") | `ntr-hr/source/nwm_rs/src/entries/start_up/safe_impl.rs` |
| Puerto destino por defecto | UDP **8001** | `constants.h` `RP_DST_PORT_DEFAULT` |
| Datagrama | ≤ 1448 bytes: cabecera 4 bytes + datos (1444 en todos salvo el último) | `ntrviewer-hr/const.h` `RP_PACKET_SIZE`, `RP_DATA_HDR_SIZE` |
| hdr[0] | id de frame (u8) | `thread_nwm/vars.rs::DataHdr::init` |
| hdr[1] | bit0 = pantalla superior, bit1 = ancho completo, bit2 = ambos ojos, bit4 (0x10) = último paquete | ídem + `ntr_rp.c::handle_recv` |
| hdr[2] | `2 \| downsample<<2 \| lossless` | ídem |
| hdr[3] | índice de paquete (u8, se desenvuelve si un frame supera 256 paquetes) | `ntr_rp.c::handle_recv` |
| Imagen | JPEG de 240×400 (superior) o 240×320 (inferior): el framebuffer va **rotado**; se muestra girado 90° antihorario (X nativa = y JPEG, Y nativa = 240 − x JPEG) | shader `vs_data_str` de `ui_renderer_ogl.c`; `Chokistream ImageManipulator.adjustStandard` |
| Reliable Stream / Delta / Lossless | KCP modificado (cid/pid, FEC "fecal") + Huffman/delta propios | `ntr_rp.c` (`handle_recv_kcp`, `ikcp.c` modificado) |

**Modos soportados por 3DS2TV v0.1:** solo **JPEG Compat (UDP)**. 3DS2TV siempre pide ese modo (args[4] sin flags).
Si llega Reliable Stream o Lossless (p. ej. porque el usuario lo eligió en el menú de la consola en modo pasivo),
3DS2TV lo **detecta y muestra un error explicativo**; no lo presenta como compatible.
Downsample (opción avanzada de la consola): 0/1 se muestran normal, 3 (cuarto) se escala, 2 (even/odd) se reconstruye.

Rendimiento esperado según el README de NTRViewer-HR (medido por su autor, no por nosotros): JPEG Compat hasta ~120 FPS
sumando ambas pantallas en el menú HOME con Wi-Fi óptimo; NTR-HR declara 60–90 FPS a calidad media. **No hemos medido
hardware real**.

### Audio DSP (NTR-HR 0.3.8+)

| Elemento | Valor | Fuente |
|---|---|---|
| Activación | args[5] > 0 en el comando 901 | `ns.c`, `thread_main.rs` |
| Transporte (modo UDP) | mismo puerto UDP que el vídeo; hdr[2] = 4, hdr[3] = 0 (PCM16), hdr[0] = secuencia del frame más reciente | `thread_audio.rs`, `ntrviewer-hr/ntr_audio.h` |
| Carga | 2 frames redundantes (anterior + actual) de 160 muestras estéreo s16le a **32728 Hz** | ídem |
| Limitación | Solo mezcla final DSP; **CSND no soportado** | README de NTR-HR |

## 4. Input Redirection confirmado (Luma3DS Rosalina)

Fuente: `Luma3DS/sysmodules/rosalina/source/input_redirection.c` (commit `1441535`).

| Offset | Contenido | Neutro |
|---|---|---|
| 0–3 | HID PAD, **activo a nivel bajo** (bit a 1 = suelto). Bits: A0 B1 Select2 Start3 →4 ←5 ↑6 ↓7 R8 L9 X10 Y11 | `0x00000FFF` |
| 4–7 | Táctil: `1<<24 \| y<<12 \| x` (0..0xFFF escalado desde 320×240); sin toque `0x02000000` | `0x02000000` |
| 8–11 | Circle Pad: `y<<12 \| x` (12 bits, centro 0x7FF; +Y = arriba) | `0x007FF7FF` |
| 12–15 | "IR" (New 3DS): `y<<24 \| x<<16 \| botones<<8 \| 0x81`; C-Stick 8 bits centro 0x80 rotado 45°; botones bit1 = ZR, bit2 = ZL | `0x80800081` |
| 16–19 | Especiales: bit0 HOME, bit1 POWER, bit2 POWER largo | `0` |

- UDP **4950** en la IP de la consola, little-endian. Rosalina ignora datagramas < 12 bytes y solo lee 16–19 si recibe ≥ 20.
- Rosalina **mantiene el último estado recibido**: por eso 3DS2TV envía de inmediato cada cambio, reenvía el estado
  cada 16 ms (≈60 Hz, ≈1,2 KB/s) para tolerar pérdidas UDP y manda 3 veces el estado neutro al salir, abrir el menú,
  pausar o desconectarse el mando.
- Menú Rosalina: combo por defecto `L + ↓ + Select` (`menu.h DEFAULT_MENU_COMBO`), opción
  "Miscellaneous options..." → "Start InputRedirection" (`menus/miscellaneous.c`).

## 5. Android TV / Chromecast

- Chromecast con Google TV 4K (2020): Wi-Fi 2,4/5 GHz, Bluetooth 4.2, HDMI 4K, USB-C (página de Google).
  Salió con Android TV 10 (API 29) y ha recibido versiones posteriores; `minSdk = 24` cubre todas sin forzar APIs nuevas
  (`lockHardwareCanvas` solo se usa en API 26+, con alternativa software).
- La UI de las apps en este dispositivo se compone normalmente a 1080p y el sistema la saca por HDMI a 4K: la imagen de
  la 3DS (400×240 / 320×240) **siempre se escala**; no hay "4K nativo".
- Mandos: Android normaliza Xbox/PlayStation/HID a `KEYCODE_BUTTON_*` y `AXIS_*`
  (developer.android.com/training/game-controllers). USB-C: requiere hub con alimentación passthrough; que funcione
  como host depende del hub y del firmware → **experimental, pendiente de validación física**.

## 6. Arquitectura elegida (resumen)

`:core` (Kotlin JVM puro: protocolo, sesión, mapeos, layout, ajustes) · `:app` (Android TV: UI D-pad, decodificador
`BitmapFactory` con reutilización de bitmaps, render `SurfaceView` por GPU, `AudioTrack`, mandos) ·
`:tools:mock-3ds-sender` (consola NTR-HR simulada para desarrollo y tests). Detalle en `ARCHITECTURE.md`.

## 7. Riesgos y limitaciones

| Riesgo | Mitigación / estado |
|---|---|
| Sin hardware real en el entorno cloud | Tests de protocolo con consola simulada + test end-to-end en emulador Android TV (CI). Validación física: `HARDWARE_VALIDATION.md`. |
| JPEG Compat pierde frames con mala Wi-Fi (sin retransmisión) | Descarte rápido de frames incompletos, métricas de pérdida, guía de red. Reliable Stream queda como trabajo futuro. |
| La consola ya emitía a otro viewer | Al recibir el 901, NTR-HR pone `dstAddr = 0` ("always update from nwm callback", `rp_menu.c::rpUpdateParamsFromMenu`) y reaprende la IP del cliente TCP, es decir, de 3DS2TV. En modo pasivo hay que poner la IP del Chromecast en "Viewer IP". |
| Audio DSP es reciente (NTR-HR 0.3.8) y no probado en hardware | Marcado experimental, desactivado por defecto, el vídeo no depende de él. |
| Mandos USB vía hub | Experimental; diagnóstico de dispositivos no reconocidos en "Mandos". |
| Pokémon USUM y otros se cuelgan al cargar con Remote Play | Recomendación de NTR-HR: activar Input Redirection o Debugger en Rosalina. |

## 8. Trabajo futuro verificable

1. Reliable Stream (KCP modificado + FEC) y Delta: requiere portar `ikcp.c` modificado (MIT) y el decodificador delta.
2. Lossless/Uncompressed: decodificador Huffman propio de NTR-HR.
3. Validación física y medición de FPS/latencia reales en New 3DS XL + Chromecast.
