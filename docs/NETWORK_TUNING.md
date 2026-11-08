# Red y rendimiento

La 3DS solo tiene Wi-Fi **2,4 GHz 802.11b/g**: suele ser el cuello de botella. El Chromecast puede ir por 5 GHz o
Ethernet (con adaptador compatible) mientras ambos estén en la **misma LAN**.

## Recomendaciones

1. **Misma red y subred** (p. ej. 192.168.1.x/24). 3DS2TV avisa si la IP de la consola no está en tu subred.
2. **Sin aislamiento de clientes** ("AP isolation", "client isolation") y **sin red de invitados**: impiden que la 3DS
   y el Chromecast se vean.
3. **Chromecast en 5 GHz o Ethernet** para dejar libre la banda de 2,4 GHz a la 3DS.
4. **Buena señal 2,4 GHz para la 3DS**: cerca del router, canal poco saturado (1, 6 u 11), evita microondas/Bluetooth intensivo cerca.
5. Lo ideal según NTR-HR: un punto de acceso 2,4 GHz dedicado solo a la 3DS, con el receptor por cable.
6. Puertos que deben poder circular dentro de la LAN: TCP 8000 (3DS2TV → 3DS), UDP 8001 (3DS → Chromecast),
   UDP 4950 (Chromecast → 3DS). No hace falta abrir nada a Internet.

## Ajustes en 3DS2TV (se envían a NTR-HR)

| Ajuste | Efecto | Sugerencia |
|---|---|---|
| Calidad JPEG | Más calidad = frames más grandes = menos FPS | 75–90 (NTR-HR: 75 → 55–75 FPS, 90 → 40–60 FPS en N2DS según su README) |
| QoS (Mbps) | Límite de ancho de banda del emisor | 12–18; más alto puede perder más paquetes |
| Prioridad + factor | La pantalla prioritaria recibe N frames por cada uno de la otra; 0 = solo la prioritaria | Factor 0 si solo juegas con la superior: máximo FPS |
| Audio | ≈205 paquetes/s de 1284 bytes (≈2 Mbit/s, cada frame va duplicado contra pérdidas) | Desactívalo si el vídeo va justo |

En la consola (X+Y → Remote Play → Advanced Options): *QTM disable* da +10–15 % de rendimiento en New 3DS según NTR-HR.

## Cómo interpretar el HUD

- **FPS recibidos** (por pantalla): frames JPEG completos que llegan. Es lo que emite la consola menos lo perdido.
- **FPS mostrados**: presentaciones con al menos un frame nuevo (nunca se cuentan duplicados).
- **Frames incompletos**: faltó algún paquete UDP (Wi-Fi). Si es alto, baja calidad/QoS o mejora la señal.
- **Descartados**: el decodificador no daba abasto y se saltó a un frame más nuevo (prioriza latencia).
- **Recepción→pantalla**: tiempo dentro del Chromecast desde el último paquete hasta presentar el frame.
  No incluye el tiempo de codificación en la 3DS ni el aire: 3DS2TV no puede medirlo sin reloj común y no lo inventa.

## Expectativas realistas

- No esperes 60 FPS garantizados: depende del juego, la calidad y la Wi-Fi de la 3DS. Mide con el HUD.
- La imagen original es 400×240 / 320×240: el Chromecast la escala; usa "Escalado entero" y filtro "Píxel nítido"
  para pixel art, o "Suave" para juegos 3D.
- Si la pérdida de paquetes es alta en JPEG Compat, los modos Reliable Stream de NTR-HR la mitigarían, pero **aún no
  están implementados en 3DS2TV** (ver `RESEARCH_AND_DECISIONS.md`).
