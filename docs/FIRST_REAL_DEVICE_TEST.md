# Primera prueba en dispositivos reales

Objetivo: comprobar por primera vez 3DS2TV con **tu New Nintendo 3DS XL** y **tu Chromecast con Google TV 4K (2020)**,
paso a paso, de forma que cualquier fallo quede localizado (red, TCP, UDP, frames, imagen, mandos, audio).
Nada de esta guía modifica la consola ni pide abrirla. Anota los resultados en `HARDWARE_VALIDATION.md`.

Tiempo estimado: 45–75 minutos. Necesitas: un PC con [ADB (platform-tools)](https://developer.android.com/tools/releases/platform-tools)
o una app de instalación en el Chromecast, la 3DS con Luma3DS + NTR-HR ya instalados (`SETUP_3DS.md`) y, para la fase C,
un mando Bluetooth.

> Regla de oro: **cambia una sola cosa cada vez** y anota lo que ves. Si algo falla, exporta el informe (paso E) antes de tocar ajustes.

---

## Fase A — APK en el Chromecast

| Paso | Qué hacer | Qué debes ver / anotar |
|---|---|---|
| A1 | GitHub → *Actions* → "Android CI" → la ejecución verde más reciente de la rama → artefacto **3DS2TV-debug-apk**. Descomprímelo. | `3DS2TV-0.1.0-debug-<commit>.apk` y `SHA256SUMS.txt` |
| A2 | Comprueba la integridad: `sha256sum -c SHA256SUMS.txt` (Windows: `certutil -hashfile <apk> SHA256` y compara). | `OK` / hash idéntico. Si no coincide, **no instales**: vuelve a descargar. |
| A3 | En el Chromecast: Ajustes → Sistema → Información → pulsa 7 veces "Compilación del SO de Android TV". Luego Ajustes → Sistema → Opciones para desarrolladores → Depuración USB (y Depuración inalámbrica si aparece). | Mensaje "Ahora eres desarrollador". |
| A4 | Conecta ADB: con *Depuración inalámbrica* → "Vincular con código" → `adb pair IP:puerto` (código) y `adb connect IP:puerto` (el otro puerto que muestra). En versiones antiguas: `adb connect IP:5555`. | `adb devices` lista el Chromecast como `device`. |
| A5 | `adb install -r 3DS2TV-*.apk` | `Success` |
| A6 | Abre **3DS2TV** desde la fila de apps **con el mando original del Chromecast**. Navega con la cruceta. | Pantalla de inicio legible; el foco (recuadro claro) se mueve con la cruceta. |
| A7 | Anota: versión de Android TV (Ajustes → Sistema → Información), y en 3DS2TV → *Diagnóstico* las dos primeras líneas (versión y commit de 3DS2TV, modelo y API) y la línea "Red (usada)". | Versión app, commit, API, IP del Chromecast y si es Wi-Fi 5 GHz / 2,4 GHz / Ethernet. |

Alternativa sin PC: ver `INSTALL_CHROMECAST.md` §6 (no necesita root).

## Fase B — Solo vídeo

Antes de empezar, en 3DS2TV → *Ajustes*: **Audio = No**; en *Mandos*: **Input Redirection = No**. Así solo se prueba el vídeo.

| Paso | Qué hacer | Qué debes ver / anotar |
|---|---|---|
| B1 | 3DS en la misma Wi-Fi (2,4 GHz). Arranca NTR-HR (BootNTRSelector, versión **no** Mode3). Pulsa **X+Y** → *Remote Play*. | Título "Remote Play (Standby): <IP de la 3DS>". Anota la IP y la versión de NTR-HR. |
| B2 | Abre un juego 3DS normal (no DS/GBA) y déjalo en una pantalla con movimiento. | — |
| B3 | En 3DS2TV: escribe la IP de la 3DS, *Modo: Conectar a la consola*, abre *Diagnóstico* → **Probar conexión (12 s)**. | Debe terminar en **RESULTADO: OK — N frames completos decodificados**. Si falla, la lista muestra en qué etapa (ver tabla de abajo). |
| B4 | Vuelve a inicio → **Conectar**. | Imagen del juego en la TV. Si no, el panel muestra la misma lista de etapas. |
| B5 | Pulsa **L3** o **BACK** del mando de TV → menú → *Mostrar*: Solo superior / Solo inferior / Ambas; *Diseño*: Vertical / Horizontal; *Inferior primero*; rota cada pantalla 90/180/270. | Cada cambio se aplica al momento, sin deformar la imagen, sin parpadeo. Anota cualquier orientación incorrecta (p. ej. imagen espejada). |
| B6 | Menú → *HUD de diagnóstico* = Sí. Deja 2 minutos. | Anota FPS recibidos (sup/inf), FPS mostrados, bitrate, frames incompletos, decodificación ms, recepción→pantalla ms. |
| B7 | Prueba calidad JPEG 70, 80 y 90 (menú → Calidad). | Anota los FPS de cada una. |

### Si la fase B falla: interpreta la lista de etapas

| Etapa con ✖ o … | Significa | Qué comprobar (solo esto) |
|---|---|---|
| Conexión TCP 8000 ✖ | La 3DS no acepta conexión en el puerto 8000 | ¿NTR-HR arrancado? ¿IP de X+Y igual a la escrita? ¿misma red (no invitados)? |
| Orden Remote Play … | No se llegó a enviar | Depende de la etapa anterior |
| Paquetes UDP … tras enviar la orden | La 3DS no emite hacia el Chromecast o algo lo bloquea | Aislamiento de clientes Wi-Fi del router; en X+Y → Remote Play mira "Viewer IP": debe ser la IP del Chromecast tras conectar |
| Paquetes UDP ✖ "llegan desde otra IP" | Hay vídeo NTR pero desde una IP distinta | Corrige la IP de la consola en 3DS2TV |
| Frames JPEG … con paquetes llegando | Se pierden paquetes y ningún frame llega completo | Baja calidad a 70 y QoS a 10; acerca la 3DS al router |
| Frames JPEG ✖ "Reliable Stream / Lossless" | La consola usa un formato no soportado | X+Y → Remote Play → Fmt/Prot: **JPEG UDP** → Apply, o usa el modo Conectar |
| Imagen presentada … con frames OK | Problema de dibujo en el Chromecast | Exporta el informe (fase E): es un fallo de 3DS2TV |

Modo pasivo (opcional, después de B4): en 3DS2TV elige *Modo: Esperar a que la consola emita* y Conectar; en la 3DS
X+Y → Remote Play → Viewer IP = IP del Chromecast (la de "IP de este dispositivo" en la pantalla de inicio), Viewer
Port 8001, Fmt/Prot JPEG UDP → Apply. Debe aparecer la imagen y "Consola detectada: <IP>".

## Fase C — Mandos

| Paso | Qué hacer | Qué debes ver / anotar |
|---|---|---|
| C1 | Empareja el mando Bluetooth: Ajustes de Google TV → Mandos remotos y accesorios → Vincular. | Modelo del mando. |
| C2 | 3DS2TV → *Mandos y pantalla táctil* → **Probar mando**. Pulsa cada botón, mueve cada stick y gatillo. | Cada control aparece en "Controles"; sticks entre −1 y +1; gatillos 0–1; línea "→ 3DS" con el botón esperado. Anota cualquier botón que no aparezca. Sal con BACK del mando de TV o Select+Start 1 s. |
| C3 | En la 3DS: **L+↓+Select** → *Miscellaneous options* → **Start InputRedirection** → debe decir "OK". | — |
| C4 | En 3DS2TV: *Input Redirection* = Sí. Conecta. Sin tocar el mando: | La 3DS no recibe pulsaciones fantasma (el estado inicial enviado es neutro). |
| C5 | Prueba **uno a uno**: cruceta, A, B, X, Y, L, R, ZL, ZR, Start, Select, Circle Pad (4 direcciones), C-Stick (4 direcciones). | Anota en la tabla cuáles responden y si alguno está invertido. |
| C6 | Liberación: mantén A pulsado y (a) abre el menú con L3, (b) apaga el mando, (c) pulsa Home del Chromecast. | En los tres casos la 3DS deja de recibir A. |
| C7 | Cursor táctil: menú → *Mostrar: Ambas*; pulsa **R3**, mueve con el stick derecho y toca con **R1** en un botón de la pantalla inferior. Repite con la inferior rotada 90°. | El toque cae donde está el cursor. Anota el desvío si lo hay. |
| C8 | (Solo si C1–C7 van bien) Mando USB con hub USB-C con carga (PD). | ¿Aparece como mando en *Mandos* o en "Otros dispositivos"? Experimental. |

Nota: Input Redirection no tiene confirmación. "Enviados" en el HUD indica datagramas enviados, no aplicados.
Con Input Redirection activo, un corte de Wi-Fi puede congelar la 3DS (aviso de NTR-HR): se recupera entrando en Rosalina y parándolo.

## Fase D — Estabilidad y audio

| Paso | Qué hacer | Qué anotar |
|---|---|---|
| D1 | Sesión de **30 minutos** jugando con HUD activo. Cada 10 min anota la línea de FPS y la de frames incompletos/descartados. | ¿Cierres? ¿La latencia percibida crece con el tiempo? ¿Botones atascados? |
| D2 | Durante la sesión, apaga la Wi-Fi de la 3DS 5 s y vuelve a activarla (o reinicia Remote Play en X+Y). | ¿Pasa a "Reconectando" y vuelve la imagen sola? ¿Cuánto tarda? Si no vuelve, prueba "Reconectar" en el menú y anótalo (riesgo conocido #16 de `CONTINUATION_AUDIT.md`). |
| D3 | Solo al final: *Audio = Sí* con un juego que tenga audio DSP. | ¿Se oye? ¿cortes? Anota juego y versión de NTR-HR. Si no hay audio y el diagnóstico dice "sin paquetes", puede ser NTR-HR < 0.3.8 o un juego CSND (no soportado). |

## Fase E — Enviar resultados sin datos privados

1. 3DS2TV → *Diagnóstico* → deja **Ocultar IPs al exportar = Sí** → *Exportar informe a archivo*.
2. En el PC: `adb pull "<ruta que muestra la pantalla>"`.
3. Ábrelo con un editor y revísalo: contiene versión, commit, modelo, ajustes, métricas, log y (si lo hubo) el último cierre inesperado. Las IPs aparecen como `192.168.x.x`. No contiene contraseñas, cuentas ni identificadores.
4. Adjunta el fichero y tu copia de `HARDWARE_VALIDATION.md` rellenada en un issue del repositorio.

Opcional, para un fallo concreto: `adb logcat -d -s 3DS2TV AndroidRuntime > logcat.txt` justo después de reproducirlo.
