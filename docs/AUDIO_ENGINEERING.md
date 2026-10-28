# Arquitectura y Diagnóstico del Subsistema de Audio (Bambi Android TV)

Este documento detalla la arquitectura de audio de Bambi, el análisis forense de los problemas detectados en dispositivos como Google TV 4K, las soluciones implementadas en la versión **v1.1.3** y el diseño técnico previsto para la **v1.1.4**.

---

## 1. Contexto y Diagnóstico Forense

### 1.1 Dispositivos con y sin Licencia Hardware (El caso DTS)
- **Homatics Box R 4K Plus / Xiaomi TV:** Incorporan licencias propietarias preinstaladas en firmware para decodificación hardware de DTS (`c2.amlogic.audio.decoder.dts`). En estos equipos, ExoPlayer envía el flujo al decodificador de hardware y reproduce audio multicanal sin problemas.
- **Chromecast con Google TV 4K (y modelos similares de bajo coste):** Para reducir costes de licencia, el fabricante no incluye soporte hardware para la familia DTS (`audio/vnd.dts`, DTS-HD MA).
- **Comportamiento en v1.1.2:**
  - Al consultar ExoPlayer al sistema si el formato es reproducible, Android responde `FORMAT_UNSUPPORTED_TYPE`.
  - La lógica de selección de pistas (`PlayerTracks.kt`) filtraba las pistas no soportadas mediante `group.isTrackSupported(i)`.
  - En películas de Moria donde la única pista en castellano es DTS 5.1 (ej. *El caballero oscuro* o *Origen*), la lista quedaba vacía y la interfaz mostraba el mensaje erróneo **"Sin pistas de audio"**, impidiendo cualquier intento de reproducción.

### 1.2 El caso E-AC-3 (Dolby Digital Plus) y Passthrough HDMI
- **Archivo analizado:** *La bola negra (2026)* (1080p, TMDB `1422041`, MKV).
  - Pista de audio: **E-AC-3 (Enhanced AC-3 / Dolby Digital Plus)**, 6 canales (**5.1 surround**), 640 kbps, 48.000 Hz, idioma español (`spa`).
- **Comportamiento observado:**
  - En la ficha aparece el identificador `[EAC3] [6ch]`.
  - En el reproductor **sí aparece la pista** en la lista de audio (a diferencia de DTS).
  - Sin embargo, al reproducir **no se escucha nada (silencio absoluto)** en el Google TV 4K.
- **Causa raíz:**
  1. Google TV 4K sí cuenta con soporte de sistema para E-AC-3, por lo que ExoPlayer valida la pista como soportada por hardware/sistema.
  2. En la v1.1.3, el decodificador de software FFmpeg se configuró con `EXTENSION_RENDERER_MODE_ON` (el hardware tiene prioridad; FFmpeg solo entra si el hardware rechaza el formato).
  3. Al aceptar el sistema la pista, ExoPlayer selecciona `MediaCodecAudioRenderer` (del sistema) y **no** usa FFmpeg.
  4. Por defecto, Google TV tiene configurado el sonido en **"Automático"**. En este modo, ExoPlayer y el sistema Android envían el flujo E-AC-3 sin descomprimir mediante **passthrough (bitstream) directo por HDMI**.
  5. Si el televisor receptor (o la barra de sonido conectada por cable óptico TOSLink) no soporta Dolby Digital Plus 5.1 o tiene limitaciones de decodificación interna, el hardware del televisor descarta los paquetes comprimidos. Resultado: el vídeo reproduce fluido, la pista está seleccionada, pero los altavoces quedan en silencio.

---

## 2. Solución Implementada en v1.1.3 (Soporte DTS y TrueHD por CPU)

Para solucionar la ausencia de sonido en contenidos DTS y TrueHD sin depender de reproductores de terceros, se integró el motor de decodificación por software oficial de Media3:

1. **Binario compilado (`media3-decoder-ffmpeg-1.6.0.aar`):**
   - Incorporado en `app/libs/` con librerías nativas `libffmpegJNI.so` para `arm64-v8a` y `armeabi-v7a`.
   - Incluye decodificadores nativos para:
     - **DTS / DTS-HD Master Audio** (`dca`).
     - **Dolby TrueHD / MLP** (`truehd`, `mlp`).
     - **AC-3 / E-AC-3** (`ac3`, `eac3`).
     - **FLAC**, **Vorbis** y **Opus**.
2. **Carga por Reflexión (`Media3Factory.kt`):**
   - Se activa `setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)`.
   - Media3 detecta la clase `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` e instancia el renderer de respaldo. Si la librería no estuviera presente (ej. en compilaciones estándar de upstream), captura la excepción de forma segura y continúa con los decodificadores de hardware.
3. **Resguardo de UI (`PlayerTracks.kt`):**
   - Si existiera una pista no soportada por hardware ni software, ya no se oculta dejando "Sin pistas de audio", sino que se muestra en la lista con el aviso explícito de compatibilidad para evitar confusión.

---

## 3. Especificación Técnica v1.1.4 (Control de Salida y Reproductor Externo)

Para solventar problemas de HDMI Passthrough (como el de E-AC-3) y dotar al usuario de contingencia absoluta, se definen dos mecanismos complementarios:

### 3.1 Forzar Decodificación por Software (PCM Downmix / CPU)
- **Mecanismo:**
  - Configurar `DefaultRenderersFactory` con `EXTENSION_RENDERER_MODE_PREFER`.
  - Al dar preferencia a las extensiones, `FfmpegAudioRenderer` se posiciona en el **índice 0** de renderers de audio, por delante de `MediaCodecAudioRenderer`.
  - FFmpeg asume la decodificación de todos los códecs soportados (E-AC-3, AC-3, DTS, TrueHD) y los convierte internamente a **PCM lineal sin comprimir** (`ENCODING_PCM_16BIT`).
  - El flujo PCM es universalmente compatible: todos los televisores, monitores y barras del mercado lo reproducen instantáneamente sin requerir decodificadores dedicados ni soporte bitstream HDMI.
- **Puntos de Integración:**
  - **Ajustes → Reproducción:** Nuevo switch persistente (`forceSoftwareAudio` en `SettingsRepository`) titulado *"Forzar audio por software (PCM)"*.
  - **Panel de Audio (Menú Pausa / Tecla Abajo):** Toggle directo en caliente para alternar el modo sin necesidad de abandonar la reproducción.

### 3.2 Integración de Reproductor Externo (Just Player / VLC)
- **Mecanismo:**
  - Invocación mediante `Intent(Intent.ACTION_VIEW)` de Android Leanback / TV.
  - Parámetros enviados:
    - `data`: URI directa del enlace resuelto de Debrid.
    - `type`: `video/*`.
    - `putExtra("title", mediaTitle)`: Título para visualización en el player externo.
    - `putExtra("position", currentPositionMs)`: Posición en milisegundos para continuar exactamente donde se pausó.
    - `putExtra("return_result", true)`: Permite recuperar el estado al volver a Bambi.
- **Puntos de Integración:**
  - **Panel de Pistas / Pausa:** Opción *"Abrir en reproductor externo ↗"*.
  - **Ajustes → Reproducción:** Selector *"Reproductor por defecto"* (Interno / Externo).

---

## 4. Política de Compatibilidad con Upstream (`grodri99/bambi`)

1. **Arquitectura No Invasiva:** El soporte de `FfmpegAudioRenderer` mediante reflexión garantiza que `grodri99` puede aceptar el Pull Request sin estar obligado a empaquetar binarios AAR pesados en su árbol principal si no lo desea.
2. **Desacoplamiento:** Las preferencias de audio y reproductor externo extienden `SettingsRepository` siguiendo los mismos patrones de `seekThumbnails` y `maxBufferSeconds`, manteniendo la paridad con futuras actualizaciones de upstream.
