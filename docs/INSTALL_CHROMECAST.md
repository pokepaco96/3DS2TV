# Instalar 3DS2TV en Chromecast con Google TV

El APK de 3DS2TV no está en Google Play. Se instala por ADB (recomendado) o con un instalador local. No hace falta root.

## 1. Descargar el APK

- **GitHub Actions:** pestaña *Actions* del repositorio → ejecución "Android CI" correcta → artefacto
  **3DS2TV-debug-apk** (zip con `3DS2TV-<versión>-debug-<commit>.apk` y `SHA256SUMS.txt`). Requiere iniciar sesión en GitHub.
- **Releases:** si hay una versión etiquetada (`v*`), el APK está adjunto en *Releases*.

Comprueba el hash: `sha256sum -c SHA256SUMS.txt`.

## 2. Activar opciones de desarrollador

1. Ajustes → **Sistema** → **Información** → pulsa 7 veces **Compilación del SO de Android TV** (Android TV OS build).
2. Ajustes → Sistema → **Opciones para desarrolladores** → activa **Depuración USB**.
3. En versiones recientes de Google TV aparece también **Depuración inalámbrica**: actívala.

(Los nombres exactos pueden variar ligeramente según la versión de Google TV.)

## 3. Conocer la IP del Chromecast

Ajustes → Red e Internet → tu red Wi-Fi → Dirección IP. También la muestra 3DS2TV en su pantalla de inicio.

## 4. Conectar ADB por red (desde un PC con [platform-tools](https://developer.android.com/tools/releases/platform-tools))

**A. Con "Depuración inalámbrica" (emparejamiento por código):**
1. En el Chromecast: Opciones para desarrolladores → Depuración inalámbrica → **Vincular dispositivo con código**.
   Muestra `IP:puerto_de_vinculación` y un código.
2. En el PC: `adb pair IP:puerto_de_vinculación` e introduce el código.
3. En la pantalla de Depuración inalámbrica aparece otro `IP:puerto` (distinto): `adb connect IP:puerto`.

**B. Versiones con depuración por red clásica:** `adb connect IP_DEL_CHROMECAST:5555` y acepta el diálogo
"¿Permitir depuración?" en la TV. No asumas que el puerto 5555 siempre está disponible: si falla, usa el método A.

Comprueba con `adb devices`.

## 5. Instalar, actualizar, desinstalar

```bash
adb install -r 3DS2TV-0.1.0-debug-xxxxxxx.apk     # instalar o actualizar (conserva ajustes)
adb uninstall com.threedstwo.tv                     # desinstalar
```
La app aparece en la fila de apps de Google TV como **3DS2TV** (si no la ves: Apps → Ver todas).
Una actualización debe estar firmada con la misma clave: los APK debug de CI se firman con la clave debug del runner,
que puede cambiar entre ejecuciones; si `adb install -r` falla con `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, desinstala primero
(perderás los ajustes).

## 6. Alternativa sin PC (sin root)

1. Instala desde Google Play una app de descarga/gestión de archivos para TV (p. ej. *Downloader* de AFTVnews o un gestor de archivos).
2. Ajustes → Sistema/Privacidad → Seguridad y restricciones → **Fuentes desconocidas** → permite esa app.
3. Descarga el APK desde la página de *Releases* (URL directa del asset) o cópialo a un pendrive conectado por hub USB-C y ábrelo con el gestor.

## 7. Emparejar un mando Bluetooth

Ajustes → **Mandos remotos y accesorios** → **Vincular mando o accesorio**, pon el mando en modo emparejamiento
(Xbox: botón de emparejar; DualShock 4: Share + PS; DualSense: Crear + PS) y selecciónalo. En 3DS2TV → Mandos verás
si Android lo reconoce como mando y podrás probarlo.

## 8. Mando USB (experimental)

El Chromecast solo tiene un USB-C, usado para alimentación. Necesitas un **hub USB-C con alimentación passthrough (PD)**
alimentado con un cargador suficiente. La compatibilidad depende del hub, de la alimentación y de que Android exponga
el mando como dispositivo HID de juego: **no está garantizada**. Si 3DS2TV → Mandos lo lista como "Otros dispositivos",
Android no lo reconoce como mando. Bluetooth es la opción recomendada.

## 9. Enviar logs si falla

- En 3DS2TV → Diagnóstico → **Exportar log a archivo**; muestra la ruta y el comando, p. ej.
  `adb pull "/storage/emulated/0/Android/data/com.threedstwo.tv/files/3ds2tv-log-20261108-120000.txt"`.
- Logcat en directo: `adb logcat -s 3DS2TV`.
