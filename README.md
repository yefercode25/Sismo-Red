# Sismo Red Comunitaria

Aplicación Android para coordinar teléfonos Android en una red local y compartir mediciones sísmicas entre los dispositivos del grupo.

## Requisitos

- Android Studio con Android SDK 37.
- JDK 17 (Android Studio incluye un JDK compatible).
- Un dispositivo o emulador con Android 7.0 (API 24) o posterior.

## Abrir y compilar

1. Abre esta carpeta en Android Studio.
2. Espera a que Gradle sincronice el proyecto.
3. Ejecuta la configuración `app` en un dispositivo o emulador.

También puedes generar el APK de depuración desde PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

El APK se guarda en `app/build/outputs/apk/debug/app-debug.apk`.

## Comunicación entre dispositivos

La aplicación descubre grupos en la red local mediante servicios de Android y permite que los equipos se unan sin introducir manualmente la dirección IP. Para probar varios dispositivos, conecta los teléfonos o emuladores a la misma red local y permite el acceso a dispositivos cercanos cuando Android lo solicite.

La medición y comunicación de esta aplicación tienen fines de coordinación comunitaria y prototipado; no sustituyen instrumentos sísmicos certificados ni sistemas oficiales de alerta.

## Recursos de marca

Los recursos oficiales del icono SVG están en `app/src/main/assets/brand/`. La tipografía Poppins se distribuye bajo la licencia OFL incluida en esa misma carpeta.

