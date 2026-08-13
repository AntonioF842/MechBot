# MechBot

MechBot es una aplicación Android escrita en Kotlin para controlar un robot mediante un teléfono Android. Permite trabajar con un único teléfono conectado directamente al hardware por USB o con dos teléfonos comunicados mediante Wi‑Fi Direct.

## Modos de uso

### Control directo

Pensado para usar un solo teléfono conectado al controlador del robot por USB Host.

- Control de movimiento: adelante, atrás, izquierda, derecha y parada.
- Envío de reportes HID de salida a un dispositivo USB compatible.
- Vista previa de la cámara local con CameraX.
- Detección de objetos en tiempo real con ML Kit.
- Superposición de la caja de detección, etiqueta, confianza y proximidad estimada.
- Parada automática al soltar un botón, perder el foco o salir de la pantalla.

### Control remoto

Pensado para usar dos teléfonos Android mediante Wi‑Fi Direct:

- **Controlador:** descubre el teléfono robot, inicia la conexión, envía movimientos y recibe video.
- **Robot:** recibe los comandos, controla el dispositivo USB HID, transmite la cámara y actualiza la interfaz de ojos.

El modo remoto incluye:

- Descubrimiento, conexión y desconexión mediante Wi‑Fi Direct.
- Inicio y detención de video desde el controlador.
- Cámara frontal en el teléfono robot, con cámara trasera como alternativa.
- Activación remota de la detección de objetos.
- Envío de frames JPEG junto con los metadatos de detección.
- Visualización de detecciones sobre el video recibido.
- Ojos animados que reaccionan al movimiento del robot.
- Vista de ojos en pantalla completa y orientación horizontal.
- Controles de prueba locales en el teléfono robot, sin conexión remota.

## Detección de objetos

La detección usa ML Kit en modo de flujo continuo. La aplicación:

- Clasifica el objeto principal cuando el modelo proporciona una etiqueta.
- Estima la proximidad a partir del tamaño de la caja delimitadora.
- Suaviza la posición, confianza y cambios de proximidad para reducir saltos visuales.
- Mantiene brevemente la última detección para evitar parpadeos.

La proximidad mostrada es una **estimación visual**, no una medición física de distancia.

## Control USB HID

`UsbKeyboardLedMotorController` busca una interfaz USB HID, preferentemente de tipo teclado, y envía reportes de salida equivalentes al estado de sus LEDs.

| Movimiento | Máscara HID |
| --- | ---: |
| Detenido | `000` |
| Adelante | `001` |
| Atrás | `010` |
| Izquierda | `011` |
| Derecha | `100` |

El controlador conectado debe aceptar estos reportes HID. Si el hardware usa otro protocolo, implementa una nueva clase basada en `MotorController` o adapta `UsbKeyboardLedMotorController`.

## Medidas de seguridad

La aplicación envía una orden de parada en varias situaciones:

- Al soltar o cancelar un botón de movimiento.
- Al pulsar `STOP`.
- Al pausar o cerrar la pantalla de control.
- Al perder el foco en el teléfono robot.
- Al desconectar Wi‑Fi Direct o el socket de comandos.
- Al superar el tiempo de espera del heartbeat remoto.

## Requisitos

- Android Studio compatible con Android Gradle Plugin `9.3.1`.
- Gradle `9.5.0`, incluido mediante Gradle Wrapper.
- JDK `21` recomendado y configurado para el daemon de Gradle.
- Android SDK `36.1` instalado.
- Android 7.0 a 9.0 (`minSdk 24`).
- Para el modo remoto: dos dispositivos Android con Wi‑Fi Direct.
- Para controlar motores: un dispositivo Android con USB Host y hardware HID compatible.

## Tecnologías principales

- Kotlin.
- AndroidX y Material Components.
- Wi‑Fi Direct (`WifiP2pManager`).
- Sockets TCP.
- CameraX.
- ML Kit Object Detection.
- Android USB Host API.

## Abrir el proyecto

1. Clona o descarga el repositorio.
2. Abre la carpeta raíz en Android Studio.
3. Configura el SDK de Android cuando el IDE lo solicite.
4. Espera a que termine la sincronización de Gradle.
5. Ejecuta la aplicación en uno o dos dispositivos físicos.

## Compilar desde terminal

En macOS o Linux:

```bash
./gradlew assembleDebug
```

En Windows:

```bat
gradlew.bat assembleDebug
```

El APK de depuración se genera en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Uso: control directo

1. Conecta el adaptador o controlador HID al teléfono mediante USB OTG.
2. Abre MechBot y selecciona **Control directo**.
3. Autoriza el acceso al dispositivo USB.
4. Concede el permiso de cámara.
5. Mantén pulsado un botón de dirección para mover el robot.
6. Suelta el botón o pulsa `STOP` para detenerlo.
7. Activa o desactiva la detección con el interruptor de la parte superior.

## Uso: control remoto

1. Instala la aplicación en ambos teléfonos.
2. En el teléfono montado en el robot, abre **Control remoto** y selecciona **Robot**.
3. Conecta y autoriza el dispositivo USB HID en el teléfono robot.
4. En el segundo teléfono, abre **Control remoto** y selecciona **Controlador**.
5. Concede los permisos de cámara y dispositivos cercanos o ubicación cuando Android los solicite.
6. Pulsa **Buscar**, selecciona el teléfono robot y pulsa **Conectar**.
7. Usa los botones de movimiento; el robot se detiene al soltar cada botón.
8. Pulsa **Iniciar video** para recibir la cámara del robot.
9. Usa el interruptor **Detección** para habilitar o deshabilitar el análisis remoto.
10. En el teléfono robot puedes abrir los ojos en pantalla completa o activar los controles de prueba sin conexión.

## Licencia

Este proyecto se distribuye bajo la licencia GNU General Public License v3.0. Consulta el archivo [`LICENSE`](LICENSE).
