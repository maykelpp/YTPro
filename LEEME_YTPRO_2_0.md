# YT Pro 2.0 — parche con las 16 funciones (sobre tu proyecto 1.3)

Este ZIP es un PARCHE: solo trae los archivos nuevos o modificados. No incluye claves, contraseñas ni
archivos de firma (tu `app/ytpro.p12` y tu `app/build.gradle` no se tocan).

## Cómo aplicarlo
1. En Termux: `cd ~/ytpro && unzip -o ~/storage/downloads/YTPro_2_0_parche.zip`
2. `git status` (si hay un rebase con conflictos, resuélvelo ANTES; no uses reset --hard ni push --force)
3. `git add -A && git commit -m "YTPro 2.0" && git push origin main`
4. GitHub > Actions: compila con `gradle assembleDebug --no-daemon`. El APK queda en
   `app/build/outputs/apk/debug/app-debug.apk` (y en Releases).

## ESTADO REAL (leer)
- NO se ha compilado: en el entorno donde se escribió no hay Gradle, SDK de Android ni internet.
  El Kotlin nuevo (~1600 líneas) solo se revisó por balance de llaves y paréntesis. Puede haber errores de compilación.
- NO se ha probado en un teléfono (Android 10-16, Galaxy A15, batería, memoria, pantalla apagada).
- Sí se ejecutó: 15 pruebas de la lógica pura (carpeta `pruebas/`) y una prueba de humo del reproductor con un
  navegador SIMULADO (23 comprobaciones). Simulada no es lo mismo que real.
- Sintaxis del JavaScript completo y XML del manifest: válidos.

## Las 16 funciones (estado según el código; ninguna probada en teléfono)
| # | Función | Estado |
|---|---------|--------|
| 1 | Ecualizador 5 bandas (60 Hz-14 kHz), presets propios, autoguardado | Escrito (WebAudio, modifica el audio real) |
| 2 | Descargas en segundo plano: servicio, notificación, pausar/reanudar/cancelar, recuperación | Escrito (nativo) |
| 3 | Biblioteca inteligente: artista/álbum/canción/duración, duplicados, escaneo automático | Escrito |
| 4 | Letras cinematográficas sincronizadas, pantalla completa, .lrc local y LRCLIB | Escrito |
| 5 | Visualizador 3D (barras en perspectiva, túnel, esfera) con audio real | Escrito; rendimiento en gama media sin medir |
| 6 | Crossfade configurable (0-12 s) entre canciones de la cola/listas | Escrito |
| 7 | Temporizador con cuenta regresiva, bajada de volumen y desactivación | Escrito |
| 8 | Mezclador de listas: combinar, reordenar, sin duplicados, guardar, mezcla inteligente | Escrito (lógica probada) |
| 9 | Temas: oscuro, claro, AMOLED, personalizado (sin reiniciar) | Escrito; el tema claro puede tener colores sueltos |
| 10 | Gestos configurables con distancia mínima anti-toques accidentales | Escrito; sin probar al tacto |
| 11 | Mini reproductor | PARCIAL: flotante y arrastrable solo DENTRO de la app. No hay ventana flotante del sistema |
| 12 | Historial y estadísticas locales | Escrito (lógica probada) |
| 13 | Editor de etiquetas que escribe en el archivo | Escrito pero es una APUESTA: usa yt-dlp `--enable-file-urls`. Solo archivos de YT Pro o carpetas con permiso de escritura |
| 14 | Normalización de volumen (análisis nativo + ajuste por canción) | Escrito |
| 15 | Media Session: notificación, pantalla de bloqueo, auriculares | Escrito; si cierras la app desde recientes la música se detiene |
| 16 | Selector de carpetas SAF con permisos persistentes | Escrito |

## Limitaciones conocidas
- La reproducción corre en el WebView (Web Audio); el servicio multimedia mantiene viva la app y reenvía los comandos.
- Android 13+: pide permiso de notificaciones y de audio. Sin permiso de audio solo se ven los archivos de YT Pro.
- Con aria2c la barra de progreso puede no moverse en algunas descargas.
- Los visualizadores y el crossfade dependen de la CPU del teléfono.
