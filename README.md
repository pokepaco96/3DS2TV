# 3DS2TV

Juega con tu **New Nintendo 3DS XL** y mira su imagen en el televisor a través de un **Chromecast con Google TV**,
sin PC, sin nube y sin Google Cast: el Chromecast ejecuta 3DS2TV como app Android TV nativa.

```
New 3DS XL (Luma3DS + NTR-HR) ──Wi-Fi/LAN──▶ 3DS2TV (APK en el Chromecast) ──HDMI──▶ TV
             ▲                                         │
             └──── mando del Chromecast → Luma3DS Input Redirection (UDP 4950) ◀┘
```

> **Estado (v0.1.0, 8-nov-2026): implementado y probado con tests automáticos y consola simulada.
> No se ha probado todavía en una New 3DS XL ni en un Chromecast reales** (el desarrollo se hizo en un entorno cloud
> sin acceso a tus dispositivos). Checklist de validación: [`docs/HARDWARE_VALIDATION.md`](docs/HARDWARE_VALIDATION.md).

## Funciones

| Función | Estado |
|---|---|
| Conexión a NTR-HR (TCP 8000, orden Remote Play) y vídeo **JPEG Compat** por UDP 8001 | ✅ Implementado · tests de protocolo, integración y emulador |
| Modo pasivo (la consola inicia Remote Play hacia el Chromecast) y aprendizaje de la IP | ✅ Implementado · test de integración |
| Solo superior / solo inferior / ambas; vertical u horizontal; intercambio de orden | ✅ Implementado · tests de layout |
| Rotación independiente 0/90/180/270°, tamaño por pantalla, separación, margen, escalado entero | ✅ Implementado · tests de layout |
| Proporción siempre conservada; filtro píxel nítido o bilineal; pantalla completa | ✅ Implementado |
| Calidad JPEG, QoS, pantalla prioritaria y factor enviados a NTR-HR en caliente | ✅ Implementado · test de integración |
| Reconexión automática con backoff, errores explicativos (IP, sin UDP, formato no soportado) | ✅ Implementado · tests de integración |
| HUD: FPS recibidos/mostrados, bitrate, pérdidas, decodificación, recepción→pantalla | ✅ Implementado |
| Ajustes persistentes, diagnóstico y log exportable | ✅ Implementado · tests de ajustes |
| Mandos Bluetooth/USB reconocidos por Android → **Luma3DS Input Redirection** (A/B/X/Y, cruceta, L/R, ZL/ZR, Start/Select, Circle Pad, C-Stick) | ✅ Implementado · tests de codificación · ⚠️ pendiente de prueba física |
| Perfiles, intercambio A/B-X/Y, reasignación, deadzone/sensibilidad, probador de mando | ✅ Implementado |
| Liberación de botones al abrir menú, pausar, desconectar mando o salir | ✅ Implementado · tests |
| Cursor táctil virtual en la pantalla inferior (con rotación/escala correctas) | ✅ Implementado · tests · ⚠️ pendiente de prueba física |
| Audio DSP de NTR-HR 0.3.8+ (AudioTrack, latencia acotada) | 🧪 Experimental, desactivado por defecto · test con audio sintético |
| Mandos USB mediante hub USB-C PD | 🧪 Experimental: depende del hub; sin probar |
| NTR-HR Reliable Stream, RS Delta, Lossless/Uncompressed | ❌ No implementado: se detecta y se muestra un aviso |
| CSND audio, juegos DS/DSiWare/GBA, Old 3DS | ❌ No soportado por NTR-HR |

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
