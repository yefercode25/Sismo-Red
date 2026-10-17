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

## Red Supabase

La app ofrece dos perfiles: **estación**, que registra un punto fijo del Guavio y publica su presencia, amplitud y anomalías, y **receptor**, que consulta el directorio de estaciones y las alertas. El receptor puede elegir una estación registrada en la lista en tiempo real o escanear su QR `sismored://station/<id>`; ambos caminos abren las lecturas de esa estación.

Para habilitar cuentas y tiempo real:

1. Crea un proyecto Supabase y ejecuta [`supabase/schema.sql`](supabase/schema.sql) en el SQL Editor. El script crea el perfil al registrar usuarios, estaciones, políticas RLS y el trigger de consenso.
2. Agrega en tu `local.properties` (archivo local ignorado por Git) la URL del proyecto y su **publishable key** (o anon key heredada):

   ```properties
   supabase.url=https://TU-PROYECTO.supabase.co
   supabase.anonKey=TU_CLAVE_PUBLICA
   ```

3. Vuelve a compilar e instalar la app. Nunca uses `service_role` en Android.

El esquema limita el registro a una estación por municipio. La tabla `stations` conserva el identificador y los datos de conexión lógica de cada punto; `station_status` mantiene la última presencia/lectura y su hora. La estación envía presencia cada cinco segundos mientras la app está abierta y no mide, o actualiza estado durante la medición. El receptor marca una estación activa cuando recibió un dato hace menos de 15 segundos. Una alerta comunitaria se inserta cuando anomalías de dos o más estaciones distintas del Guavio llegan al servidor dentro de una ventana de 10 segundos, aunque estén en municipios vecinos. El esquema incluye `stations`, `alerts` y `station_status` en la publicación `supabase_realtime`.

## Recursos de marca

Los recursos oficiales del icono SVG están en `app/src/main/assets/brand/`. La tipografía Poppins se distribuye bajo la licencia OFL incluida en esa misma carpeta.
