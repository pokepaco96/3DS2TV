# Avisos de terceros

## Código derivado incluido

### NTRViewer-HR — MIT
https://github.com/xzn/ntrviewer-hr (commit `556c6d0`)

Partes derivadas en 3DS2TV:
- `core/src/main/kotlin/com/threedstwo/tv/core/ntr/Downsample.kt`: port de `screen_process()` (`ntr_rp.c`).
- `core/src/main/kotlin/com/threedstwo/tv/core/input/ThreeDsInput.kt` (`StickMath`): fórmula de rotación de 45° del
  C-Stick y rango del Circle Pad tomados de `ui_input_redirection.c`.

```
MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Proyectos usados solo como referencia técnica (sin copiar código)

| Proyecto | Licencia | Uso |
|---|---|---|
| [NTR-HR](https://github.com/xzn/ntr-hr) | GPLv2 | Formato de paquetes, puertos, comando 901, audio DSP |
| [Luma3DS](https://github.com/LumaTeam/Luma3DS) | GPLv3 | Formato de Input Redirection (UDP 4950) |
| [Chokistream](https://github.com/Eiim/Chokistream) | GPLv2 | Contraste de orientación de imagen |
| [3DSnickerStream](https://github.com/samaBR85/3DSnickerStream) | GPLv3 | Contraste de formatos NTR-HR |
| [InputRedirectionClient-Qt-Android](https://github.com/JambonBeurreMan/InputRedirectionClient-Qt-Android) | MIT | Contraste endianess y bits ZL/ZR |

Los hechos de interoperabilidad (puertos, offsets, máscaras) se implementaron de forma independiente.

## Dependencias de compilación/test (no incluidas en el APK salvo stdlib)

| Componente | Licencia |
|---|---|
| Kotlin standard library (en el APK) | Apache 2.0 |
| Android Gradle Plugin, Gradle | Apache 2.0 |
| JUnit 4 (solo tests) | EPL 1.0 |
| AndroidX Test runner / ext-junit (solo tests instrumentados) | Apache 2.0 |

Nintendo 3DS es una marca de Nintendo. Chromecast y Google TV son marcas de Google. 3DS2TV no está afiliado a ninguna de ellas.
