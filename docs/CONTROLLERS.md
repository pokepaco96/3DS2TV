# Mandos, mapeos y pantalla táctil

## Cómo llega el mando a la 3DS

`Mando (Bluetooth/USB)` → Android (`KeyEvent`/`MotionEvent`) → 3DS2TV → **UDP 4950** → Luma3DS Rosalina Input Redirection.
Es independiente del vídeo de NTR-HR. 3DS2TV solo envía a la IP de la consola configurada (o aprendida en modo pasivo).
Requisito en la consola: Rosalina (L+↓+Select) → Miscellaneous options → **Start InputRedirection**.

Formato exacto de los paquetes: ver `RESEARCH_AND_DECISIONS.md` §4 (verificado en el código de Luma3DS, con tests en
`core/src/test/.../InputRedirectionTest.kt`).

## Mapeo por defecto ("Por posición", como en la 3DS)

| Mando (posición) | Xbox | PlayStation | 3DS |
|---|---|---|---|
| Botón derecho | B | ○ | **A** |
| Botón inferior | A | ✕ | **B** |
| Botón superior | Y | △ | **X** |
| Botón izquierdo | X | □ | **Y** |
| LB / L1 · RB / R1 | | | **L · R** |
| LT / L2 · RT / R2 (gatillo analógico o botón) | | | **ZL · ZR** (New 3DS) |
| View/Back/Share · Menu/Start/Options | | | **SELECT · START** |
| Cruceta (teclas o HAT) | | | Cruceta |
| Stick izquierdo | | | **Circle Pad** (analógico) |
| Stick derecho | | | **C-Stick** (analógico, rotado 45° como espera la consola) |
| L3 (clic stick izq.) | | | Abrir menú de 3DS2TV |
| R3 (clic stick der.) | | | Activar/desactivar cursor táctil |
| Guía / Home / PS | | | Sin asignar (HOME de la 3DS desactivado por defecto) |

Perfiles: *Por posición* (por defecto), *Por letra (Xbox)* (A del mando = A de la 3DS), *PlayStation* y *Genérico*
(estos dos equivalen a "por posición"; Android ya normaliza los botones de estos mandos). Botón **Intercambiar A/B y
X/Y** y **reasignación individual** de cada control en *Mandos y pantalla táctil*.

POWER de la 3DS no se puede asignar (riesgo de apagar la consola). HOME solo si activas "Permitir HOME de la 3DS".

## Sticks

- Zona muerta radial y sensibilidad configurables por stick (Circle Pad 15 % y C-Stick 20 % por defecto).
- Escala del Circle Pad: ±0x5D0 alrededor de 0x800 (el mismo rango que usa NTRViewer-HR).

## Seguridad: botones nunca atascados

Rosalina conserva el último estado recibido. 3DS2TV:
- envía cada cambio al instante y repite el estado cada 16 ms (≈60 Hz) para tolerar pérdidas UDP;
- manda el estado **neutro** (todo suelto, sticks al centro, sin toque) al abrir el menú, al pasar la app a segundo
  plano, al desconectarse el mando activo, al desconectar la sesión y al salir (3 veces).

## Varios mandos

"Mando principal": *el primero que pulse un botón* (por defecto) o uno concreto. Solo el mando activo controla la
consola; si entra en suspensión y se reconecta, vuelve a tomar el control al pulsar un botón, sin cortar la sesión.

## Menú y salir

- Abrir/cerrar el menú en sesión: **L3** (configurable), mantener **Select + Start 1 s**, o **BACK/OK** del mando de TV.
- Dentro del menú se navega con la cruceta; A acepta, B cierra.
- El botón **Home** del sistema nunca se intercepta: siempre puedes salir.

## Pantalla táctil inferior (cursor virtual)

1. Muestra la pantalla inferior (Ambas o Solo inferior).
2. Pulsa **R3** (o activa "Cursor táctil" en el menú). Aparece un cursor sobre la pantalla inferior.
3. Mueve el cursor con el **stick derecho** (o izquierdo / cruceta, configurable). Mientras el cursor está activo,
   ese stick no se envía como C-Stick/Circle Pad.
4. **Toca** con el botón configurado (por defecto **R1**: mientras el cursor está activo R1 toca en lugar de ser R).
5. Pulsa R3 otra vez para volver al control normal.

La posición se guarda en coordenadas nativas (320×240), así que el toque es correcto aunque la pantalla inferior esté
rotada, ampliada o colocada en cualquier lado (probado en `LayoutTest`). Velocidad configurable.

## Mando del Chromecast

Navega toda la interfaz. Opcional "Usar mando de TV como control básico": cruceta = cruceta 3DS, OK = A; BACK sigue
abriendo el menú. No sustituye a un mando completo.

## Bluetooth y USB

- **Bluetooth (recomendado):** Ajustes de Google TV → Mandos remotos y accesorios → Vincular. El Chromecast tiene Bluetooth 4.2.
- **USB (experimental):** hub USB-C con carga passthrough (PD). Puede no funcionar según hub/alimentación. 3DS2TV →
  Mandos lista los dispositivos de entrada y separa los que Android no expone como mando (diagnóstico).
- Compatibilidad **no probada en hardware** por el autor de esta versión: usa *Probar mando* y anota resultados en
  `HARDWARE_VALIDATION.md`.
