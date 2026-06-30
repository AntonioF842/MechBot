# MechBot

Aplicación Android en Kotlin para controlar un robot desde otro teléfono usando Wi‑Fi Direct. El proyecto incluye dos modos de uso: **Controlador** y **Robot**. El modo controlador envía comandos de movimiento, mientras que el modo robot recibe los comandos, actualiza la interfaz de ojos del robot, transmite cámara frontal y envía reportes HID por USB para controlar el hardware conectado.

## Funciones principales

- Descubrimiento y conexión entre dispositivos mediante Wi‑Fi Direct.
- Envío de comandos por socket TCP entre controlador y robot.
- Transmisión básica de video con CameraX desde el dispositivo robot.
- Interfaz de “ojos” del robot, con modo de pantalla completa.
- Soporte USB Host para dispositivos HID tipo teclado, usado para enviar estados de LEDs como señales de control.

## Requisitos

- Android Studio compatible con Gradle 9.x y Android Gradle Plugin 9.x.
- JDK 21 recomendado.
- Android SDK con API 36 instalada.
- Dispositivo Android con Android 7.0 o superior (`minSdk 24`).
- Para probar todas las funciones: dos dispositivos Android con Wi‑Fi Direct; el dispositivo robot debe soportar USB Host si se usará el control HID.

## Abrir el proyecto

1. Clona o descarga este repositorio.
2. Abre el proyecto en Android Studio.
3. Espera a que termine la sincronización de Gradle.
4. Ejecuta la app en uno o dos dispositivos físicos.

## Compilar desde terminal

En macOS o Linux:

```bash
./gradlew assembleDebug
```

En Windows:

```bat
gradlew.bat assembleDebug
```

El APK de debug se generará en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Uso básico

1. Instala la app en ambos teléfonos.
2. En el teléfono que irá montado en el robot, selecciona **Modo robot**.
3. En el otro teléfono, selecciona **Modo controlador**.
4. Acepta los permisos de cámara y Wi‑Fi/dispositivos cercanos cuando Android los solicite.
5. Desde el controlador, busca dispositivos, selecciona el teléfono robot y conecta por Wi‑Fi Direct.
6. Usa los botones de movimiento para enviar comandos.
7. Usa los controles de video para iniciar o detener la transmisión de cámara del robot.
8. En modo robot puedes activar la vista de ojos en pantalla completa.

## Notas de hardware

La clase `UsbKeyboardLedMotorController` busca un dispositivo USB HID tipo teclado y envía reportes de salida para modificar el estado de LEDs. Si tu controlador de motores usa otro protocolo, adapta esa clase o crea una nueva implementación de `MotorController`.

## Licencia

GPLv3
