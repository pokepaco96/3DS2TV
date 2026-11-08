# 3DS2TV

Juega con tu **New Nintendo 3DS XL** y mira su imagen en el televisor a través de un **Chromecast con Google TV**,
sin PC, sin nube y sin Google Cast: el Chromecast ejecuta 3DS2TV como app Android TV nativa.

```
New 3DS XL (Luma3DS + NTR-HR) ──Wi-Fi/LAN──▶ 3DS2TV (APK en el Chromecast) ──HDMI──▶ TV
             ▲                                         │
             └──── mando del Chromecast → Luma3DS Input Redirection (UDP 4950) ◀┘
```

> **Estado: build candidata a primera prueba física (v0.1.0).** Implementado y probado con **simulador** (consola NTR-HR
> falsa por red real) y en **emulador** Android TV API 31 (GitHub Actions). **No validado todavía en una New 3DS XL ni en
> un Chromecast reales.** Guía de la primera prueba: [`docs/FIRST_REAL_DEVICE_TEST.md`](docs/FIRST_REAL_DEVICE_TEST.md) ·
> registro: [`docs/HARDWARE_VALIDATION.md`](docs/HARDWARE_VALIDATION.md) · auditoría: [`docs/CONTINUATION_AUDIT.md`](docs/CONTINUATION_AUDIT.md).

## Funciones y nivel de evidencia

Leyenda: ✅ sí · — no aplica / no probado · ❌ no implementado. "Simulador" = tests JVM con `FakeNtrConsole` por sockets
reales; "Emulador" = test instrumentado en Android TV API 31 x86; "Hardware" = New 3DS XL + Chromecast reales.

| Función | Implementado | Tests unitarios | Simulador | Emulador | Hardware real |
|---|---|---|---|---|---|
| NTR-HR JPEG Compat: TCP 8000, orden 901, vídeo UDP 8001 | ✅ | ✅ | ✅ | ✅ | pendiente |
| Modo pasivo (la consola inicia) y aprendizaje seguro de la IP | ✅ | — | ✅ | — | pendiente |
| Reconexión, errores explicados, checklist por etapas (TCP / 901 / UDP / frame completo / imagen / mandos) | ✅ | ✅ | ✅ | parcial (STREAMING + imagen) | pendiente |
| Prueba de conexión en Diagnóstico (OK solo con JPEG decodificados) | ✅ | — | — | — | pendiente |
| Solo superior / inferior / ambas; vertical/horizontal; intercambio; rotación y tamaño por pantalla | ✅ | ✅ | — | dibujo ✅ | pendiente |
| Proporción conservada, píxel nítido/bilineal, escalado entero | ✅ | ✅ | — | — | pendiente |
| Calidad JPEG / QoS / prioridad enviadas en caliente | ✅ | ✅ | ✅ | — | pendiente |
| HUD de métricas y exportación de informe (IPs ocultables, commit, último crash) | ✅ | ✅ (máscara IP) | — | — | pendiente |
| Luma3DS Input Redirection (UDP 4950, 20 bytes) | ✅ | ✅ (fixtures hex) | ✅ | ✅ (paquetes neutros) | pendiente |
| Mandos Android: mapeo, perfiles, A/B-X/Y, reasignación, deadzone, ejes por dispositivo, probador | ✅ | ✅ | — | — | pendiente |
| Liberación de botones (menú, pausa, mando desconectado, salir) | ✅ | ✅ | ✅ | — | pendiente |
| Cursor táctil con rotación/escala | ✅ | ✅ | — | — | pendiente |
| Audio DSP de NTR-HR 0.3.8+ (experimental, desactivado por defecto) | ✅ | ✅ | ✅ (sintético) | — | pendiente |
| Mandos USB por hub USB-C PD (experimental) | ✅ (si Android lo expone) | — | — | — | pendiente |
| Reliable Stream / RS Delta / Lossless | ❌ (se detectan y se avisa) | ✅ (detección) | ✅ (aviso) | — | — |
| Audio CSND, juegos DS/DSiWare/GBA, Old 3DS | ❌ no soportado por NTR-HR | | | | |

Tests automáticos (medidos en este commit): `:core` 67 · `:tools:mock-3ds-sender` 23 (red real por loopback) ·
`:app` 2 JVM + 2 instrumentados (emulador). Ninguno sustituye a la prueba física.

## Requisitos

- New 3DS / New 3DS XL / New 2DS XL con Luma3DS actualizado y **NTR-HR** (versión no-Mode3). Guía: [`docs/SETUP_3DS.md`](docs/SETUP_3DS.md).
- Chromecast con Google TV (o cualquier Android TV 7.0+ / API 24+).
- Ambos en la misma red local, sin aislamiento de clientes. Recomendaciones: [`docs/NETWORK_TUNING.md`](docs/NETWORK_TUNING.md).

## Instalar y jugar (resumen)

1. Descarga el APK: GitHub → *Actions* → última ejecución verde de "Android CI" → artefacto **3DS2TV-debug-apk**
   (o *Releases* si hay una versión etiquetada).
2. Instálalo: `adb connect <IP-chromecast>:<puerto>` y `adb install -r 3DS2TV-*.apk`. Paso a paso: [`docs/INSTALL_CHROMECAST.md`](docs/INSTALL_CHROMECAST.md).
3. En la 3DS: arranca NTR-HR (BootNTRSelector), mira la IP con **X+Y**; para el mando, Rosalina (**L+↓+Select**) →
   *Miscellaneous options* → *Start InputRedirection*.
4. En el Chromecast: abre 3DS2TV, escribe la IP de la 3DS y pulsa **Conectar**.
5. Durante el juego: **L3** (o Select+Start 1 s, o BACK del mando de TV) abre el menú; **R3** activa el cursor táctil.
   Mandos y mapeos: [`docs/CONTROLLERS.md`](docs/CONTROLLERS.md).

## Compilar

Requisitos: JDK 17+ y Android SDK (platform 35). Desde un clon limpio:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # o exporta ANDROID_HOME
./gradlew test :app:lintDebug :app:assembleDebug   # tests JVM + lint + APK
# APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest           # con un emulador/dispositivo conectado
./gradlew :tools:mock-3ds-sender:installDist       # consola NTR-HR simulada para desarrollo
```

Versiones: Gradle 8.14.3 (wrapper con SHA-256), Android Gradle Plugin 8.13.2, Kotlin 2.2.21, compileSdk/targetSdk 35, minSdk 24.
Firma release: CI publica APK **debug** (instalable por ADB). Para una release firmada, genera tu propio keystore
fuera del repositorio y configura `signingConfigs` con variables de entorno/secretos de GitHub; nunca subas claves.

## Estructura

| Ruta | Contenido |
|---|---|
| `core/` | Protocolo NTR-HR, sesión, Input Redirection, mapeos, layout, ajustes (Kotlin JVM, sin Android) |
| `app/` | App Android TV |
| `tools/mock-3ds-sender/` | Consola NTR-HR simulada + tests de integración |
| `docs/` | [Investigación y decisiones](docs/RESEARCH_AND_DECISIONS.md) · [Arquitectura](docs/ARCHITECTURE.md) · [Plan de pruebas](docs/TEST_PLAN.md) · [Validación física](docs/HARDWARE_VALIDATION.md) |
| `.github/workflows/` | CI (tests, lint, APK, emulador Android TV) y releases por etiqueta |

## Privacidad

Sin telemetría, cuentas, anuncios ni servicios externos. Solo se comunica con la IP de la consola en tu red local.

## Licencia

MIT ([`LICENSE`](LICENSE)). Créditos y licencias de terceros en [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
Gracias a xzn (NTR-HR, NTRViewer-HR), cell9 (NTR), el equipo de Luma3DS y los autores de Chokistream,
Snickerstream/3DSnickerStream e InputRedirectionClient.
3DS2TV no está afiliado a Nintendo ni a Google.
