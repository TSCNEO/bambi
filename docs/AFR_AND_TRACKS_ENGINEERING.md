# Ingeniería de Auto Frame Rate (AFR) y Navegación de Pistas en Android TV (v1.1.5 - v1.1.6)

## 1. Problema de Auto Frame Rate (AFR)

### Síntomas observados
Al reproducir contenidos a 23.976 fps o 24.0 fps (por ejemplo, *Dune: Parte dos* en remux MKV) en dispositivos Android TV como la Homatics Box R 4K Plus o Shield TV, la salida HDMI del televisor permanecía clavada a 60 Hz (`VIC:97` en `/sys/class/amhdmitx/amhdmitx0/disp_mode`), a pesar de que el ajuste del sistema operativo `match_content_frame_rate` estuviera fijado en `2` (Seamless / Non-seamless siempre activo). Como consecuencia, se producían microtirones (*judder*) debidos a la cadencia 3:2 pulldown.

### Diagnóstico de causa raíz forense
1. **Comportamiento por defecto de Media3 / ExoPlayer**:
   ExoPlayer llama internamente a `Surface.setFrameRate(fps, C.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)`. En Android 11+, esto se traduce por omisión en una estrategia `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`. Salvo en televisores y cadenas HDMI 2.1 con QMS, Android TV descarta el cambio no-seamless y preserva los 60 Hz.
2. **Ausencia de tasa de refresco en contenedores Matroska (`.mkv`)**:
   `MatroskaExtractor` en `androidx.media3` **nunca invoca `setFrameRate`** en el `Format.Builder` de vídeo porque la especificación MKV define duraciones de muestra opcionales a nivel de bloque y no una cabecera global estandarizada de fps.
   Por consiguiente, en casi la totalidad de remuxes y archivos MKV (incluyendo Debrid y Torrentio), `player.videoFormat?.frameRate` y `trackFormat.frameRate` devuelven `-1.0f` (`Format.NO_VALUE`). Las comprobaciones condicionales `fps > 0f` descartaban la ejecución de AFR silenciosamente.
3. **Mecanismo universal en Android TV**:
   Para conmutar modos no-seamless sin QMS, se debe asignar la propiedad de ventana:
   `WindowManager.LayoutParams.preferredDisplayModeId = mode.modeId` en la `Activity`.

### Solución implementada (`AutoFrameRateManager.kt` en v1.1.6)
1. **Detección dual de frecuencia (Metadatos + Muestreo de PTS en tiempo real)**:
   - Si el contenedor expone directamente la tasa (ej. MP4 o MKV con cabecera), se aplica de forma instantánea al pasar a `STATE_READY`.
   - Si el contenedor reporta `-1.0f` (MKV / Remux), se registra un `VideoFrameMetadataListener` en `ExoPlayer`. Al renderizarse los primeros 12 fotogramas, se calculan las diferencias de marcas de tiempo de presentación (`presentationTimeUs`):
     $$\Delta t \approx 41.708\,\mu\text{s} \implies \text{fps} = \frac{1\,000\,000}{\Delta t} \approx 23.976\,\text{fps}$$
   - Una vez calculada la tasa real con precisión de milisegundos, se despacha la conmutación al hilo principal de la interfaz (`mainHandler.post`) y se desvincula el listener para no generar sobrecarga.
2. **Inspección de modos y algoritmo de coincidencia**:
   - Inspecciona `display.supportedModes` y filtra únicamente los que coinciden con la resolución nativa física (3840x2160 en paneles 4K).
   - Coincidencia exacta con tolerancia < 0.02 Hz (ej. 23.976 fps -> modo 109 a 23.976025 Hz; 24.0 fps -> modo 110 a 24.000002 Hz).
   - Múltiplos enteros suaves (ej. 25.0 fps -> 50.0 Hz).
3. **Ciclo de vida acoplado y restauración**:
   - Al cerrar la reproducción o pausar, `restoreDefault(activity)` restaura `preferredDisplayModeId = 0` (60 Hz del sistema operativo).
4. **Ajuste en interfaz**:
   - Toggle en Ajustes -> Reproducción: *"Ajuste automático de frecuencia (AFR)"* (activado por defecto).

---

## 2. Problema del Selector de Pistas y Reproductor Externo (Foco D-pad en Compose)

### Síntomas observados
Al reproducir películas y presionar ABAJO en el mando a distancia, el panel de Audio y Subtítulos se abría visualmente en pantalla, pero no respondía a ninguna tecla del mando (Arriba, Abajo, Izquierda, Derecha, OK). No era posible resaltar ninguna opción ni abrir el reproductor externo.

### Diagnóstico de causa raíz forense
1. **Atrapamiento de foco en el Box raíz (`PlayerScreen.kt`)**:
   El contenedor principal `Box` de la pantalla del reproductor tenía asignado `.focusable()` incondicional. Durante la reproducción, el Box raíz capturaba el foco de Compose. Al abrir `tracksOpen = true`, el Box raíz continuaba reteniendo el foco (`focused = true`), impidiendo que el algoritmo de navegación espacial 2D de Compose TV descendiera hacia los elementos hijos dentro del panel de pistas.
2. **Orden de modificadores en `OptionRow` (`PlayerTracks.kt`)**:
   El modificador `.focusRequester(...)` se pasaba como parámetro y se concatenaba antes de `.tvClickable(...)`. En Compose 1.5+, el `FocusTargetModifierNode` lo genera el propio clicable; al estar el requester por encima, la solicitud de foco `initialFocus.requestFocus()` fallaba o quedaba huérfana.
3. **Falta de agrupación de foco (`focusGroup`)**:
   El panel de pistas no declaraba `Modifier.focusGroup()`, lo que impedía que Compose tratara las columnas de Audio y Subtítulos como destinos independientes de salto D-pad horizontal.

### Solución implementada (v1.1.6)
1. **Liberación de foco en el contenedor raíz**:
   ```kotlin
   .focusable(enabled = !tracksOpen && !syncOpen)
   ```
   Al abrir el panel de pistas, el contenedor raíz desactiva su capacidad de foco, forzando a Compose a delegar el foco inmediatamente a los componentes internos del panel.
2. **Encadenamiento directo en `OptionRow`**:
   ```kotlin
   Row(
       modifier = Modifier
           .fillMaxWidth()
           .clip(RoundedCornerShape(6.dp))
           .background(if (focused) Color.White else Color.Transparent)
           .tvClickable(onClick = onClick)
           .then(modifier) // focusRequester aplicado directamente sobre el nodo enfocable
           .onFocusChanged { focused = it.isFocused }
           .padding(horizontal = 14.dp, vertical = 10.dp),
   )
   ```
3. **Agrupación espacial de navegación**:
   Se añadió `Modifier.focusGroup()` a la fila principal de dos columnas y a cada `LazyColumn`, permitiendo navegar libremente entre opciones con ARRIBA/ABAJO y alternar entre Audio y Subtítulos con IZQUIERDA/DERECHA.
4. **Acceso directo en `Key.DirectionDown`**:
   Cualquier pulsación de ABAJO durante la reproducción activa directamente `tracksOpen = true`, mostrando el panel y otorgando el foco a la primera pista de forma instantánea.
