# Ingeniería de Auto Frame Rate (AFR) y Navegación de Pistas en Android TV (v1.1.5 - v1.1.7)

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
2. **Encadenamiento directo en `OptionRow`** (corregido definitivamente en v1.1.7, ver §3.4):
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

---

## 3. v1.1.7 — Enclavamiento AFR de un solo disparo y foco determinista

La v1.1.6 dejó el AFR funcionando (el modo HDMI se aplicaba), pero en dispositivos reales aparecieron dos
regresiones derivadas de la **reactividad**: el AFR se re-evaluaba en mitad de la reproducción y la
`Activity` se recreaba al conmutar el modo. v1.1.7 convierte el AFR en un evento de un solo disparo y hace
determinista el reparto de foco.

### 3.1 Recreación de la `Activity` al conmutar el modo HDMI

**Síntoma.** Al arrancar una película a 23.976 fps, el televisor parpadeaba (negro) y la reproducción se
reiniciaba; en algunos MKV entraba en un bucle de parpadeo. `dumpsys activity` mostraba
`MainActivity` destruida y recreada inmediatamente después de cada cambio de modo.

**Causa raíz.** `activity.window.attributes.preferredDisplayModeId = modeId` cambia la configuración de
pantalla del sistema (`screenSize`, `smallestScreenSize`, `screenLayout` y, en algunos SoC Amlogic,
`uiMode`). `AndroidManifest.xml` no declaraba `android:configChanges` para `MainActivity`, así que Android
consideraba que la Activity no podía manejar el cambio por sí misma y la destruía por completo:

1. `onDispose` del `DisposableEffect` se ejecuta → `restoreDefault()` vuelve a 60 Hz.
2. La Activity se recrea → `STATE_READY` → `attach()` vuelve a pedir 24 Hz.
3. Goto 1: bucle de parpadeo, y el reproductor se desmonta una y otra vez.

**Solución.** Declarar que la Activity maneja todos esos cambios de configuración, igual que hace Just
Player (referencia de facto del AFR en Android TV):

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="landscape"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode|touchscreen"
    android:launchMode="singleTask">
```

Con esto Android no recrea la Activity: entrega `onConfigurationChanged` y la ventana (y el reproductor)
siguen vivos durante la conmutación. `touchscreen` se incluye porque el launcher de TV reporta
capacidades de entrada distintas tras el cambio de modo en algunos firmwares.

### 3.2 Guarda *single-shot* en `AutoFrameRateManager.attach()`

**Síntoma.** Durante la reproducción se producían micro-conmutaciones de HDMI (parpadeo + reajuste de
audio) al abrir el panel de pistas, al cambiar de subtítulo o al activar el audio por software.

**Causa raíz.** `attach()` era idempotente solo en apariencia: cada invocación volvía a llamar a `detach()`,
`extractFrameRate()` y, si aún no había modo aplicado, registraba un nuevo `VideoFrameMetadataListener`.
Los eventos `onTracksChanged` / `onVideoSizeChanged` de `PlayerScreen` lo invocaban en cada cambio de pista,
así que el listener de PTS se reinstalaba y podía recalcular y reaplicar el modo sobre el stream ya
ajustado. Si el cálculo daba un fps ligeramente distinto (p. ej. 23.98 por ruido de PTS), el modo cambiaba
otra vez.

**Solución.** Enclavamiento en la entrada del método:

```kotlin
fun attach(activity: Activity, player: Player) {
    if (currentAppliedModeId != 0) {
        Log.i(TAG, "AFR: modo ya enclavado (modeId=$currentAppliedModeId), se ignora nuevo attach()")
        return
    }
    detach(activity)
    ...
}
```

`currentAppliedModeId != 0` significa "este stream ya tiene su modo HDMI". Cualquier llamada posterior
(reapertura del panel, cambio de pista, evento de tamaño de vídeo, recomposición) es un no-op: no se
recalcula nada ni se registra un segundo listener.

### 3.3 Limpieza inmediata del listener en `AfrVideoFrameListener.dispatchApply()`

**Causa raíz.** El listener se desenganchaba **dentro** del `mainHandler.post { }`, es decir, un
frame-loop completo después de decidir el modo. En ese hueco seguía recibiendo
`onVideoFrameAboutToBeRendered` y, si la conmutación tardaba (cambio de modo no-seamless ~1 s), podía
volver a entrar en la rama de muestreo con `ptsList` ya consumido.

**Solución.** Desenganchar antes de publicar la conmutación en el hilo principal:

```kotlin
private fun dispatchApply(act: Activity, fps: Float) {
    runCatching { exoPlayer.clearVideoFrameMetadataListener(this) }
    if (activeListener === this) activeListener = null
    mainHandler.post { applyFrameRate(act, fps) }
}
```

`clearVideoFrameMetadataListener` es seguro desde el hilo de render de Media3 (delega internamente al
hilo de aplicación) y `runCatching` protege el caso de un player ya liberado.

### 3.4 `restoreDefault()` libera el enclavamiento

Como `attach()` ahora cortocircuita con `currentAppliedModeId != 0`, la restauración tiene que dejar el
estado limpio para la **siguiente** reproducción:

```kotlin
fun restoreDefault(activity: Activity) {
    detach(activity)          // quita cualquier VideoFrameMetadataListener pendiente
    currentAppliedModeId = 0  // libera el enclavamiento del stream anterior
    ...
    lp.preferredDisplayModeId = 0
    window.attributes = lp
}
```

Sin este reset, el segundo vídeo de la sesión nunca conmutaría (el gestor es `@Singleton`, vive más que la
pantalla de reproducción y arrastraría el `modeId` del vídeo anterior). Nótese que la comprobación para
escribir en la ventana ahora es solo `lp.preferredDisplayModeId != 0`: el reset de estado ocurre siempre,
incluso si la ventana ya estaba a 0.

### 3.5 `PlayerScreen`: fuera los disparadores reactivos de AFR

En el `DisposableEffect(player, autoFrameRate, activity)` se eliminan `onTracksChanged` y
`onVideoSizeChanged` del `Player.Listener`. Queda **solo** `onPlaybackStateChanged == STATE_READY`.

Motivo: ambos callbacks se emiten muchas veces por stream (cambio de pista de audio, subtítulo externo
adjunto, adaptación de tamaño al primer keyframe, reapertura del panel). Combinados con el listener de PTS
eran la fuente principal de re-aplicaciones espurias. El AFR debe decidirse una vez, cuando el pipeline
está listo, y no volver a tocarse hasta `onDispose`.

### 3.6 Foco del D-pad: `SurfaceView` y reclamación al arrancar fotogramas

Tres ajustes para que el foco de Compose no se pierda cuando el vídeo empieza a pintar:

1. **`AndroidView(PlayerView).update`**: se añade `disableDescendantFocus(view)`, que recorre el árbol de
   vistas (`PlayerView → SurfaceView`, `SubtitleView`) y fuerza en cada nodo `isFocusable = false`,
   `isFocusableInTouchMode = false` y, si es `ViewGroup`, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`.
   El `PlayerView` recrea su `SurfaceView` al recomponer o al cambiar el modo HDMI, y la nueva instancia
   nace enfocable aunque la anterior no lo fuera; por eso el refuerzo va en `update` y no solo en `factory`.
2. **`LaunchedEffect(state, tracksOpen, playback.isPlaying)`**: se añade `playback.isPlaying` como clave.
   Con una fuente que se resuelve asíncronamente (Debrid / Torrentio tardan 2-4 s en `Resolving`), el
   `AndroidView` se inserta antes de que haya fotogramas y el `SurfaceView` puede quedarse con el foco en
   el primer frame; reclamar solo al cambiar `state` no cubre ese caso. Al pasar `isPlaying` de `false` a
   `true` se vuelve a pedir `focusRequester.requestFocus()`.
3. **`OptionRow` (`PlayerTracks.kt`)**: cadena de modificadores corregida. `onFocusChanged` va **antes**
   de `clip`/`background` para que el estado `focused` se actualice en el mismo pase de composición que
   pinta el fondo blanco, y `tvClickable` va **después** de `then(modifier)` para que el `focusRequester`
   del ítem (`initialFocus`) caiga sobre el nodo enfocable real:

   ```kotlin
   Row(
       modifier = Modifier
           .fillMaxWidth()
           .onFocusChanged { focused = it.isFocused }
           .clip(RoundedCornerShape(6.dp))
           .background(if (focused) Color.White else Color.Transparent)
           .then(modifier)
           .tvClickable(onClick = onClick)
           .padding(horizontal = 14.dp, vertical = 10.dp),
   )
   ```

   Respecto a v1.1.6, `onFocusChanged` sube al principio (antes se aplicaba al final, después del
   clickable, con lo que el resaltado blanco llegaba un frame tarde o no llegaba) y `tvClickable` baja
   después del `focusRequester` (antes quedaba por encima del requester y los clics del D-pad sobre el
   ítem enfocado por `initialFocus` no se entregaban).

### 3.7 Resumen de ficheros tocados en v1.1.7

| Fichero | Cambio |
|---|---|
| `app/src/main/AndroidManifest.xml` | `android:configChanges` completo en `.MainActivity` (§3.1) |
| `core/media/.../AutoFrameRateManager.kt` | guarda single-shot en `attach`, limpieza inmediata del listener, reset en `restoreDefault` (§3.2-3.4) |
| `feature/player/.../PlayerScreen.kt` | sin `onTracksChanged`/`onVideoSizeChanged`; `disableDescendantFocus`; `isPlaying` como clave del foco (§3.5-3.6) |
| `feature/player/.../PlayerTracks.kt` | orden de modificadores en `OptionRow` (§3.6) |
| `app/build.gradle.kts` | `versionCode = 117`, `versionName = "1.1.7"` |

### 3.8 Invariantes que no deben romperse

- **Compatibilidad upstream (Media3):** todos los cambios usan APIs públicas de Media3 1.6.0
  (`VideoFrameMetadataListener`, `Display.Mode`, `Player.Listener`) y de la plataforma Android. No hay
  forks ni parches sobre las clases de `androidx.media3`, así que `git merge upstream/main` sigue siendo
  viable y los parches sobre ficheros del upstream siguen siendo quirúrgicos.
- **El AFR se aplica una sola vez por stream.** Si alguna vez se necesita re-evaluar (p. ej. contenido con
  tasa variable), hay que llamar primero a `restoreDefault()`; volver a invocar `attach()` no sirve.
- **`versionName` debe seguir siendo SemVer puro** (`1.1.7`, sin sufijos): `UpdateRepositoryImpl` parte por
  `.` y `-` y descarta segmentos no numéricos.
