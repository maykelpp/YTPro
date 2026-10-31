YTPro — compilación Android local
=================================

Arquitectura
------------
- Aplicación Android nativa en Kotlin.
- WebView local servida por NanoHTTPD en 127.0.0.1:8731.
- yt-dlp y FFmpeg se ejecutan dentro del dispositivo mediante youtubedl-android.
- Los archivos temporales y descargas se guardan en el almacenamiento privado de la app.
- No se configura ningún servidor remoto ni API externa de descarga.
- GitHub Actions solo compila el APK; no participa en el funcionamiento de la aplicación.

Requisitos de red
-----------------
"100 % local" significa que el procesamiento y el servidor de la aplicación están
en el teléfono. Internet sigue siendo necesario para consultar y descargar vídeos.
No significa que el contenido pueda descargarse sin conexión.

Compatibilidad realista
-----------------------
- YouTube: extracción mediante yt-dlp; puede cambiar cuando la plataforma actualiza
  sus sistemas.
- Instagram, Facebook, X y Reddit: depende del extractor, de que el contenido sea
  público y de las restricciones de cada sitio.
- TikTok: la integración Android incluida no incorpora curl_cffi. Cuando el extractor
  exige impersonación TLS, puede fallar con "no impersonate target available" o
  "Unable to extract webpage video data". Cambiar únicamente la versión de
  youtubedl-android no agrega esa dependencia.
- Contenido privado, restringido por edad, protegido por inicio de sesión o cuya
  descarga no esté autorizada no se garantiza ni se intenta eludir.

Cambios de esta revisión
-----------------------
- Los errores de extracción ahora se explican por plataforma.
- Se eliminó el mensaje engañoso que recomendaba actualizar la biblioteca como
  solución universal.
- Se conserva el modo totalmente local y la API local existente.
- No se agregó telemetría, backend remoto ni servicio de descarga de terceros.

Compilación
-----------
El workflow .github/workflows/build.yml compila con Java 17 y Gradle 8.9.
En GitHub: Actions > Build APK > Artifacts > YTPro-apk.
El APK resultante es app-debug.apk.
