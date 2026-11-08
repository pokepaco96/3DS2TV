# Validación física — registro

**Estado: ninguna fila validada.** Hasta que estas filas estén rellenadas con hardware real, 3DS2TV se considera
"implementado y probado con simulador y emulador", **no** "validado en New 3DS XL / Chromecast".
Procedimiento detallado: [`FIRST_REAL_DEVICE_TEST.md`](FIRST_REAL_DEVICE_TEST.md).

## Equipo

| Dato | Valor |
|---|---|
| Fecha | |
| 3DS2TV versión / commit (Diagnóstico) | |
| SHA-256 del APK comprobado | |
| Chromecast: modelo / versión Android TV / API | Chromecast con Google TV 4K (2020) / / |
| Conexión del Chromecast | ☐ Wi-Fi 5 GHz ☐ Wi-Fi 2,4 GHz ☐ Ethernet |
| Consola / Luma3DS / NTR-HR | New 3DS XL / v / v |
| Router y distancia 3DS–router | |
| Mando(s) | |

## Resultados

| # | Prueba (fase) | Resultado esperado | ¿OK? | Observado / métricas |
|---|---|---|---|---|
| A6 | Instalación y navegación con mando de TV | Pantalla de inicio navegable, foco visible | ☐ | |
| B3 | Probar conexión (12 s) | RESULTADO: OK con N frames decodificados | ☐ | Etapa fallida si no: |
| B4 | Imagen en TV (modo activo) | Imagen del juego | ☐ | |
| B5a | Solo superior / solo inferior / ambas | Correctas, sin deformar | ☐ | |
| B5b | Vertical / horizontal / inferior primero | Correctas | ☐ | |
| B5c | Rotación 90/180/270 de cada pantalla | Orientación correcta | ☐ | |
| B6 | Métricas 2 min (calidad 80) | — | ☐ | FPS sup/inf: · mostrados: · kbit/s: · incompletos: · decod ms: · recep→pantalla ms: |
| B7 | Calidad 70 / 80 / 90 | — | ☐ | FPS 70: · 80: · 90: |
| B-pas | Modo pasivo | Imagen + "Consola detectada" | ☐ | |
| C2 | Probar mando (todos los controles) | Todos detectados | ☐ | Faltan: |
| C4 | Estado neutro al activar Input Redirection | Sin pulsaciones fantasma | ☐ | |
| C5 | Botones, cruceta, L/R, ZL/ZR, Start/Select, Circle Pad, C-Stick en juego | Todos responden, sin ejes invertidos | ☐ | Juego: |
| C6 | Liberación (menú / mando apagado / Home) | La 3DS deja de recibir el botón | ☐ | |
| C7 | Cursor táctil (normal y rotado) | Toque donde está el cursor | ☐ | Desvío: |
| C8 | Mando USB vía hub PD (experimental) | Reconocido o diagnosticado | ☐ | Hub/mando: |
| D1 | 30 minutos | Sin cierres, latencia estable, sin botones atascados | ☐ | 10/20/30 min: |
| D2 | Corte Wi-Fi 5 s | Reconexión automática | ☐ | Tiempo: |
| D3 | Audio DSP (experimental) | Sonido sin cortes graves | ☐ | Juego / NTR-HR: |

Adjunta el informe exportado (Diagnóstico → Exportar, con IPs ocultas) al registrar estos resultados.
