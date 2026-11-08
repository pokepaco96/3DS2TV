# Validación física (pendiente)

**Estado: NADA de esta lista se ha probado todavía en una New 3DS XL ni en un Chromecast real.** El desarrollo se hizo en
un entorno cloud sin acceso a tus dispositivos. Rellena esta tabla al probar y, si algo falla, adjunta el log exportado
(Diagnóstico → Exportar log) en un issue.

Equipo: New 3DS XL · Luma3DS v____ · NTR-HR v____ · Chromecast con Google TV 4K (Android TV ____) · 3DS2TV v____ (commit ____)
Red: router ____ · Chromecast por ☐ 5 GHz ☐ 2,4 GHz ☐ Ethernet · distancia 3DS–router ____ m

| # | Prueba | Pasos | Resultado esperado | ¿OK? | Observado (FPS/HUD, notas) |
|---|---|---|---|---|---|
| 1 | Instalación y arranque | `adb install -r`, abrir desde la fila de apps | Icono/banner 3DS2TV, pantalla de inicio navegable con el mando de TV | ☐ | |
| 2 | Conexión NTR-HR | NTR-HR activo, IP correcta, Conectar | "Recibiendo imagen" en < 5 s | ☐ | |
| 3 | Pantalla superior | Mostrar: Solo superior | Imagen correcta, sin espejo/rotación errónea, proporción 5:3 | ☐ | FPS recibidos: |
| 4 | Pantalla inferior | Mostrar: Solo inferior | Imagen correcta 4:3 | ☐ | |
| 5 | Ambas, vertical y horizontal | Menú → Diseño | Sin deformar, centradas, separación ajustable | ☐ | |
| 6 | Intercambio y rotación | Inferior primero; rotar cada pantalla 90/180/270 | Se aplican al instante, sin tearing | ☐ | |
| 7 | Mando Bluetooth | Emparejar, Mandos → Probar mando | Botones/sticks/gatillos detectados | ☐ | Modelo: |
| 8 | Mando USB (hub USB-C PD) | Conectar con hub alimentado | Listado como mando o diagnosticado como no reconocido | ☐ | Hub: |
| 9 | Input Redirection en juego | Rosalina → Start InputRedirection; jugar | A/B/X/Y, L/R, ZL/ZR, Start/Select, cruceta, Circle Pad y C-Stick responden; latencia percibida | ☐ | Juego: |
| 10 | Cursor táctil | R3, mover, R1 para tocar; probar con rotación de la inferior | El toque cae donde está el cursor | ☐ | |
| 11 | Reconexión | Apagar/encender Wi-Fi de la 3DS o reiniciar NTR-HR | "Reconectando" y vuelve la imagen sin reiniciar la app | ☐ | |
| 12 | Sesión de 30 min | Jugar 30 min | Sin cierres, sin latencia creciente, sin botones atascados | ☐ | Frames incompletos/descartados al final: |
| 13 | Audio DSP | Activar audio, juego con audio DSP | Sonido en la TV, sin cortes graves | ☐ | Juego: |
| 14 | Botones liberados | Mantener un botón y: abrir menú / apagar mando / pulsar Home | La 3DS deja de recibir el botón | ☐ | |
| 15 | Modo pasivo | Viewer IP = Chromecast en NTR-HR, JPEG UDP, Apply | Imagen sin TCP, IP aprendida | ☐ | |

Medidas a anotar del HUD: FPS recibidos sup/inf, FPS mostrados, bitrate, frames incompletos, decodificación (ms),
recepción→pantalla (ms) para calidades 70 / 80 / 90.
