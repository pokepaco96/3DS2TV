# Changelog

## [Sin publicar] — rama `claude/ultra-low-latency` (desde `db91d9e`)

### Corregido
- Un frame antiguo que llegaba completo después de uno más nuevo se mostraba (salto atrás). Ahora "gana el más reciente" por pantalla, tolerando el reinicio del contador de NTR-HR.
- Las estadísticas de "Última sesión" se capturaban después de liberar la sesión (0 kbit/s, "sin IP de consola").
- Un cambio de ajustes podía perderse sin llegar a la consola (carrera en `pendingRequest`).
- Cerrar la sesión bloqueaba el hilo principal hasta ~1,5 s; callbacks de sesiones viejas podían afectar a la nueva.
- "Píxel perfecto" no era entero con tamaños de pantalla personalizados; orígenes en medio píxel.
- Un JPEG con cabecera corrupta podía provocar una reserva de memoria enorme en la ruta de reserva del decodificador.
- Audio: escrituras parciales sin contabilizar; tras un cambio de salida (HDMI) el audio quedaba mudo para siempre.

### Rendimiento / latencia
- Sticks y gatillos sin el lote de eventos por vsync de Android (`requestUnbufferedDispatch`, Android 11+).
- Validación JPEG de 22–33 ns antes de decodificar (una sola decodificación por frame).
- Perfiles Ultra baja latencia / Equilibrado / Calidad y calidad JPEG adaptativa con histéresis.
- Sin asignaciones por frame en el render (matrices cacheadas).
- Métricas p50/p95 de decodificación, dibujo, recepción→pantalla y mando→envío en el HUD; estado del ajuste realmente enviado a NTR-HR.

### Herramientas
- Benchmarks JVM reproducibles (`-Pbench`) y paso informativo en CI.
- `mock-3ds-sender`: ids de frame por pantalla reiniciados en cada 901 (como NTR-HR), semilla, jitter, corrupción, TCP lento, TCP descartado.

## 0.1.0 — primera build candidata (`36e8dca`, `7dbc806`)
Ver `docs/CONTINUATION_AUDIT.md` y `docs/HARDWARE_VALIDATION.md`.
