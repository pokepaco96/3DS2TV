# Preparar la New Nintendo 3DS XL

> 3DS2TV **no** modifica tu consola. Todo lo de esta guía lo haces tú, siguiendo las guías oficiales.
> Fuentes: [Hacks Guide – 3DS](https://3ds.hacks.guide/), [Hacks Guide – Wireless streaming](https://wiki.hacks.guide/wiki/3DS:Wireless_streaming),
> [Hacks Guide – Input redirection](https://wiki.hacks.guide/wiki/3DS:Input_redirection), [NTR-HR](https://github.com/xzn/ntr-hr),
> [Luma3DS](https://github.com/LumaTeam/Luma3DS). Descarga solo de esos enlaces oficiales (Releases de GitHub), nunca de mirrors.

## Requisitos

- **New 3DS, New 3DS XL o New 2DS XL.** Las 3DS/3DS XL/2DS originales no están soportadas por NTR-HR
  (rendimiento de 3–7 FPS según su README).
- Custom firmware **Luma3DS actualizado** instalado según https://3ds.hacks.guide/ y, a ser posible, el último
  firmware del sistema. NTR-HR se cuelga si Luma3DS es demasiado antiguo y Remote Play no funciona con firmware muy bajo.
- Wi-Fi de 2,4 GHz (la 3DS no usa 5 GHz) en **la misma red local** que el Chromecast.

## 1. Instalar NTR-HR

1. Ve a https://github.com/xzn/ntr-hr/releases y descarga la última versión (en noviembre de 2026: serie **0.3.8.x**).
2. Sigue las instrucciones del propio release/README para copiar BootNTRSelector y los ficheros de NTR-HR a la SD.
3. En una New 3DS XL usa la versión **normal (no "Mode3")**. Mode3 es solo para juegos de memoria extendida en Old 3DS y tiene menos opciones.

## 2. Arrancar NTR-HR y ver la IP

1. Conecta la consola a tu Wi-Fi (Configuración de la consola → Ajustes de Internet).
2. Ejecuta **BootNTRSelector** desde el menú HOME y arranca NTR-HR.
3. Abre el menú NTR-HR con **X + Y**. En **Remote Play** el título muestra la IP de la consola, p. ej.
   `Remote Play (Standby): 192.168.1.50`. Apúntala.
4. Vuelve al menú HOME y abre el juego que quieras (puedes cambiar de juego sin cortar el stream con NTR-HR).

## 3. (Para jugar con mando) Activar Input Redirection

1. Abre Rosalina con **L + ↓ + Select** (combinación por defecto de Luma3DS).
2. Entra en **Miscellaneous options...** → **Start InputRedirection**. Debe aparecer `Starting InputRedirection... OK.`
3. Cierra Rosalina con B.

Aviso de NTR-HR: con Input Redirection o el Debugger activos, si se corta la Wi-Fi la consola puede quedarse
congelada; para recuperarla vuelve a Rosalina y desactívalo. Activar Input Redirection es también la solución
recomendada para que Pokémon USUM (y otros) no se cuelguen al cargar partida con Remote Play.

## 4. Conectar desde 3DS2TV

**Modo recomendado (Conectar):** en 3DS2TV escribe la IP de la consola y pulsa **Conectar**. 3DS2TV abre TCP 8000,
envía la orden Remote Play pidiendo **JPEG por UDP** al puerto 8001 de este Chromecast y NTR-HR empieza a emitir.

**Modo pasivo (la consola inicia):** en 3DS2TV elige "Modo: Esperar a que la consola emita" y pulsa Conectar (la IP
puede quedar vacía). En la 3DS: X+Y → Remote Play → **Viewer IP** = IP del Chromecast (se ve en la pantalla de inicio
de 3DS2TV), **Viewer Port** = 8001, **Fmt/Prot: JPEG UDP** → **Apply**.

## 5. Solución de problemas

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| "No responde IP:8000" | NTR-HR no arrancado, IP equivocada, consola en otra red | Abre X+Y para confirmar la IP; asegúrate de que BootNTRSelector arrancó NTR-HR. |
| "Acepta la conexión pero no llega vídeo UDP" | Aislamiento de clientes Wi-Fi / red de invitados / firewall del router | Desactiva "AP isolation" o usa la red principal; ambos en la misma subred. |
| "Reliable Stream / Lossless no soportado" | En el menú de la consola está elegido otro formato (modo pasivo) | X+Y → Remote Play → Fmt/Prot: **JPEG UDP** → Apply, o usa el modo Conectar. |
| Imagen a saltos, frames incompletos altos | Interferencias 2,4 GHz, señal débil | Baja calidad JPEG (70–80) o QoS; acerca la 3DS al router; ver `NETWORK_TUNING.md`. |
| Se ve un juego de DS/DSiWare/GBA en negro | No soportado por NTR-HR (modo de compatibilidad de la CPU) | No hay solución con este método. |
| Algunos juegos no emiten | Incompatibilidad conocida de NTR-HR | Prueba otra versión de NTR-HR o el juego en otro momento; no depende de 3DS2TV. |
| El mando no hace nada | Input Redirection no iniciado o IP distinta | Paso 3; en 3DS2TV → Diagnóstico mira "Input: → IP:4950". |
| Sin audio | NTR-HR < 0.3.8, audio no activado, juego CSND | Activa audio en 3DS2TV (Ajustes); los juegos CSND no tienen audio; alternativa: cable 3,5 mm. |

## Advertencias

- No todos los títulos son compatibles. **DS, DSiWare y GBA no se pueden emitir.**
- El rendimiento depende del juego, la señal y la calidad elegida. No hay garantía de 60 FPS.
- **Audio:** solo DSP en NTR-HR compatibles (0.3.8+); **CSND no soportado**.
