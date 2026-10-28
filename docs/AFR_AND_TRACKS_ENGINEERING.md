# Ingeniería de Auto Frame Rate (AFR) y Navegación de Pistas en Android TV (v1.1.5)

## 1. Problema de Auto Frame Rate (AFR)

### Síntomas observados
Al reproducir contenidos a 23.976 fps o 24.0 fps (por ejemplo, *Dune: Parte dos*) en dispositivos Android TV como la Homatics Box R 4K Plus o Shield TV, la salida HDMI del televisor permanecía clavada a 60 Hz (`VIC:97` en `/sys/class/amhdmitx/amhdmitx0/disp_mode`), a pesar de que el ajuste del sistema operativo `match_content_frame_rate` estuviera fijado en `2` (Seamless / Non-seamless siempre activo). Como consecuencia, se producían microtirones (*judder*) debidos a la cadencia 3:2 pulldown.

### Diagnóstico de causa raíz
1. **Comportamiento por defecto de Media3 / ExoPlayer**:
   ExoPlayer llama internamente a `Surface.setFrameRate(fps, C.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)`. En Android 11+, esto se traduce por omisión en una estrategia `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`.
2. **Limitación de hardware / HDMI del televisor**:
   Salvo en televisores y cadenas HDMI 2.1 con soporte explícito de QMS (Quick Media Switching), cualquier cambio entre 60 Hz y 24 Hz requiere una resincronización HDMI (*handshake* no-seamless con pantalla negra breve). Android TV, al recibir una petición catalogada como `ONLY_IF_SEAMLESS`, **descarta el cambio de modo** y preserva los 60 Hz del lanzador.
3. **Mecanismo universal en Android TV**:
   Reproductores de referencia en Android TV (como Just Player o Kodi) fuerzan la conmutación explícita mediante la propiedad de ventana:
   `WindowManager.LayoutParams.preferredDisplayModeId = mode.modeId`
   Bambi carecía de integración con los modos de pantalla de la ventana del sistema.

### Solución implementada (`AutoFrameRateManager.kt`)
1. **Inspección de modos**: Obtiene los modos soportados de la pantalla (`display.supportedModes`).
2. **Filtrado por resolución física**: Filtra únicamente los modos que coinciden con la resolución nativa actual del display (ej. 3840x2160) para no degradar la resolución de la interfaz.
3. **Algoritmo de coincidencia de frecuencia**:
   - Coincidencia exacta con tolerancia < 0.02 Hz (ej. 23.976 fps -> modo 69 a 23.976025 Hz; 24.0 fps -> modo 70 a 24.000002 Hz).
   - Múltiplos enteros suaves para contenidos europeos (ej. 25.0 fps -> modo 65 a 50.0 Hz).
4. **Ciclo de vida acoplado**:
   - `applyForPlayer(...)` detecta la tasa real a través de `player.videoFormat` o de las pistas activas de Media3.
   - Aplica `preferredDisplayModeId` al arrancar el stream.
   - En `onDispose` o al salir del reproductor, asigna `preferredDisplayModeId = 0`, restaurando de inmediato los 60 Hz del sistema para un desplazamiento fluido por los menús.
5. **Ajuste en interfaz**:
   - Nuevo toggle en Ajustes -> Reproducción: *"Ajuste automático de frecuencia (AFR)"* (activado por defecto).

---

## 2. Problema del Selector de Pistas y Reproductor Externo en Streams No Directos (Torrentio)

### Síntomas observados
En fuentes directas, al presionar ABAJO en el mando a distancia aparecía el selector de pistas de audio, subtítulos y la opción de reproductor externo. Sin embargo, en streams resueltos dinámicamente a través de Torrentio / Real-Debrid, pulsar ABAJO no abría ningún selector.

### Diagnóstico de causa raíz
1. **Lógica condicional de tecla en `PlayerScreen.kt`**:
   ```kotlin
   Key.DirectionDown, Key.Menu, Key.Captions -> {
       if (controlsVisible || event.key != Key.DirectionDown) {
           controlsVisible = false
           tracksOpen = true
       } else {
           showControls()
       }
   }
   ```
   Si los controles de reproducción (barra de progreso y tiempo) no estaban previamente en pantalla, `DirectionDown` entraba por el `else`, mostrando los controles durante 4 segundos en vez de abrir el panel de pistas. Requería una doble pulsación confusa.
2. **Pérdida de foco del D-pad durante la resolución asíncrona**:
   Las fuentes directas resuelven en 0 ms. Torrentio emite estados de progreso durante 2 a 3 segundos (`StatusOverlay`). Al terminar la resolución y cambiar el estado a `PlayerUiState.Playing`, el `LaunchedEffect` de foco no se reactivaba porque `tracksOpen` no había cambiado.
3. **Captura de foco por `SurfaceView` en `PlayerView`**:
   El componente nativo `PlayerView` no tenía declarada la propiedad `descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS`. En Android TV, la jerarquía de vistas de Android intentaba asignar el foco a la superficie nativa, privando a los modificadores `.onKeyEvent()` de Compose de recibir las pulsaciones del mando.
4. **Carrera de foco inicial en `TracksView`**:
   El panel de pistas usaba `withFrameNanos` para solicitar el foco inicial. En Compose para TV, si la medición del `LazyColumn` aún no ha terminado, `requestFocus()` falla silenciosamente en el primer frame.

### Solución implementada
1. **Acceso directo en `Key.DirectionDown`**: Ahora cualquier pulsación de ABAJO durante la reproducción activa directamente `tracksOpen = true`, mostrando el panel de Audio, Subtítulos y Reproductor externo en un solo clic.
2. **Re-enfoque al pasar a `Playing`**:
   ```kotlin
   LaunchedEffect(state, tracksOpen) {
       if (!tracksOpen && state is PlayerUiState.Playing) {
           delay(50)
           runCatching { focusRequester.requestFocus() }
       }
   }
   ```
3. **Bloqueo de foco en `PlayerView`**:
   ```kotlin
   PlayerView(ctx).apply {
       useController = false
       keepScreenOn = true
       isFocusable = false
       isFocusableInTouchMode = false
       descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
   }
   ```
4. **Retardo seguro de foco en `TracksView`**: `delay(60)` para garantizar que el primer elemento (`a_sw_audio`) quede enfocado y resaltado de forma inmediata al abrirse el panel.
