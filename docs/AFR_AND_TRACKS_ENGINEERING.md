# Ingeniería de Auto Frame Rate (AFR) y Navegación de Pistas en Android TV (v1.1.5 - v1.1.8)

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

---

## 4. v1.1.8 — PTS real frente al 24.0 mentiroso, viewport del panel de pistas y D-pad en sincronización

v1.1.7 dejó el AFR con un solo disparo y el foco determinista, pero en la Homatics seguía entrando en
**24.000 Hz (Modo 150)** con películas a 23.976 fps, el panel de pistas no enfocaba nada en películas con
muchos audios (*Dune: Parte dos*) y el mando quedaba muerto mientras la barra de sincronización manual de
subtítulos estaba abierta. Los tres fallos comparten causa: una fuente de verdad poco fiable (el entero 24
del decodificador, el viewport por defecto de `LazyColumn`, el foco del contenedor) aceptada sin
verificarla.

### 4.1 `MediaFormat.KEY_FRAME_RATE` del decodificador Amlogic devuelve 24 para 23.976

**Síntoma.** Con un mkv de 23.976 fps, `dumpsys display` mostraba el modo de 24.000002 Hz en lugar del
23.976025 Hz. La diferencia es de 1 fotograma cada ~41 s: en una escena con paneo lento se percibe como un
micro-salto aislado, imposible de distinguir del judder general sin medir.

**Causa raíz.** `AfrVideoFrameListener.onVideoFrameAboutToBeRendered` consultaba, antes de muestrear PTS:

```kotlin
if (mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
    mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE)  // Amlogic: 24.0f para un máster 24000/1001
}
```

`MediaFormat` es el objeto del **decodificador de hardware** (en Homatics/Xiaomi, Amlogic), no del
contenedor. Su `KEY_FRAME_RATE` es un `frame-rate` declarativo que el chipset rellena con el entero más
próximo, así que 23.976 se reporta como 24.0. Como esa rama se ejecutaba antes del muestreo y además
marcaba `completed = true`, los PTS nunca llegaban a medirse: el AFR aplicaba 24.0 y desenganchaba el
listener. El mismo sesgo afecta a `format.frameRate` en MP4 y en muchas cabeceras MKV.

**Solución.** Dos capas:

1. **Fuera la lectura de `KEY_FRAME_RATE`.** El listener ya no toca `mediaFormat` en absoluto. Los fps
   salen exclusivamente de los `presentationTimeUs` de los fotogramas renderizados.
2. **Banda ambigua.** Ni `format.frameRate` ni el `frameRate` del contenedor se aceptan si caen en
   `23.70..24.10`; ahí la única fuente válida es el muestreo PTS. `attach()` aplica el valor del contenedor
   solo si es concluyente (25, 50, 59.94, 60…) y, si no, registra el listener.

```kotlin
if (format.frameRate > 0f && !isAmbiguousRate(format.frameRate)) { ... }
// KEY_FRAME_RATE: no se lee nunca. Ver §4.1.
```

### 4.2 Cálculo de fps robusto ante fotogramas perdidos

**Causa raíz.** El cálculo anterior ordenaba los PTS y promediaba **todas** las diferencias en el rango
5..100 ms, sin filtrar outliers:

```kotlin
val delta = sorted[i] - sorted[i - 1]
if (delta in 5_000L..100_000L) { totalDurationUs += delta; validDeltas++ }
```

Un solo fotograma perdido durante el arranque (doble delta de ~83 ms, habitual tras el primer keyframe o
un seek) entraba en la media y hundía el resultado: **21.80 fps** en lugar de 23.976 para un solo hueco en
11 deltas, y 17.9 fps con dos. `normalizeFps` no reconocía esos valores, `findBestDisplayMode` no
encontraba coincidencia y **el AFR no aplicaba nada** — el fallo intermitente que se veía como "a veces no
cambia".

**Solución.** `measureFpsFromPts()` calcula deltas **consecutivos** (sin ordenar: el decoder entrega en
orden de presentación), descarta los que salen de 10..200 fps y los que se desvían más de un **25 % de la
mediana**. Solo entonces promedia y aplica `1_000_000 / avgDeltaUs`. Si quedan menos de 9 deltas limpios,
devuelve `null` y el listener sigue muestreando en vez de conmutar con basura.

```kotlin
val median = deltas.sorted()[deltas.size / 2]
val clean = deltas.filter { abs(it - median) <= median * 0.25f }
return (1_000_000.0 / (clean.sum().toDouble() / clean.size)).toFloat()
```

Medido con PTS sintéticos de un máster 24000/1001 (deltas 41708/41709 µs): **23.976 fps** con y sin
fotograma perdido (antes: 21.80 fps con el hueco). Máster 24.000 (41667 µs): **23.9998 fps**.

### 4.3 `normalizeFps` y desempate 23.976 vs 24.000

- La banda de 23.976 se ensancha a `23.70..23.99` (antes empezaba en 23.75 y la frontera con 24.000 estaba
  en 23.99, dejando 23.9760 justo en el borde). El muestreo de un máster 24000/1001 da 23.9755..23.9765 y
  cae a la izquierda → **23.976025 Hz, Modo 149 en Homatics**. El de un máster 24.000 da 23.9998..24.0000 y
  cae a la derecha → **24.000002 Hz, Modo 150**.
- `findBestDisplayMode` ya no usa `firstOrNull` sobre la lista de modos del sistema (el orden de
  `display.supportedModes` no está garantizado si ambos modos caen dentro de la tolerancia de 0.02 Hz).
  Ahora elige el más cercano y, ante empate, prioriza 23.976: ~99 % del cine comercial está masterizado en
  23.976p, así que en un panel que soporta ambos el modo correcto es el de 23.976025 Hz.

### 4.4 Panel de pistas: el ítem activo debe existir en el primer frame

**Síntoma.** *Dune: Parte dos* (10-15 pistas de audio entre idiomas y comentarios) abría el panel sin
ningún ítem resaltado; el foco no aparecía hasta pulsar ABAJO una vez. Con 2-3 pistas funcionaba siempre.

**Causa raíz.** Dos fallos encadenados:

1. `OptionColumn` creaba la `LazyColumn` sin `state`, con el viewport en el índice 0. La pista activa (p.
   ej. índice 8, el doblaje castellano de un remux) quedaba fuera de pantalla y `LazyColumn` **no compone
   los ítems que no son visibles**. El `Modifier.focusRequester(initialFocus)` de ese ítem no existía, así
   que el `requestFocus()` no tenía destino y fallaba en silencio.
2. `LaunchedEffect(Unit)` corría una sola vez, en el primer pase de composición, cuando `audio` podía
   estar aún vacío (`player.currentTracks` se puebla de forma asíncrona). Aunque el ítem llegara después, el
   efecto ya había consumido su única ejecución.

**Solución.**

```kotlin
// OptionColumn
val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = if (focusIndex >= 0) (focusIndex - 1).coerceAtLeast(0) else 0,
)
LazyColumn(state = listState, ...)

// TracksView
LaunchedEffect(audioFocusIndex) {
    delay(60)
    runCatching { initialFocus.requestFocus() }
}
```

El `-1` deja ver la opción anterior como contexto (el ítem activo no queda pegado al borde superior) y el
`coerceAtLeast(0)` protege el caso `focusIndex == 0`. El `delay(60)` da a la `LazyColumn` tiempo para
completar medición y layout con el scroll inicial ya colocado; `audioFocusIndex` como clave reinicia el
efecto cuando la lista real de pistas llega y cambia cuál es la activa.

### 4.5 D-pad muerto con la barra de sincronización de subtítulos abierta

**Síntoma.** Con un subtítulo externo cargado, "Sincronizar subtítulos" abre una barra donde ◀▶ ajustan
±100 ms, ▲▼ ±1 s y OK lanza el autoajuste. Ninguna tecla respondía.

**Causa raíz.** El `Box` raíz de `PlayerScreen` llevaba `.focusable(enabled = !tracksOpen && !syncOpen)`.
La barra de sincronización **no tiene componentes enfocables propios**: sus atajos viven en
`handleKey`, que se ejecuta en el `.onKeyEvent` del propio Box. Al abrirla, el Box dejaba de ser enfocable,
Compose soltaba el foco (`FocusedWindows: <none>` en `dumpsys input`) y los `KeyEvent` nunca llegaban. El
`!tracksOpen` sí es necesario (el panel de pistas tiene su propio árbol de foco), pero `!syncOpen` no tiene
justificación.

**Solución.**

```kotlin
.focusable(enabled = !tracksOpen)
```

Regla general: desactivar el foco del contenedor solo cuando el overlay tenga destinos de foco propios.

### 4.6 Resumen de ficheros tocados en v1.1.8

| Fichero | Cambio |
|---|---|
| `core/media/.../AutoFrameRateManager.kt` | fuera `KEY_FRAME_RATE`; banda ambigua en `attach`/listener; `measureFpsFromPts` con filtro de mediana; `normalizeFps` 23.70..23.99; desempate 23.976 en `findBestDisplayMode` (§4.1-4.3) |
| `feature/player/.../PlayerTracks.kt` | `rememberLazyListState(initialFirstVisibleItemIndex = focusIndex - 1)`; `LaunchedEffect(audioFocusIndex)` con `delay(60)` (§4.4) |
| `feature/player/.../PlayerScreen.kt` | `.focusable(enabled = !tracksOpen)` — D-pad operativo con `syncOpen` (§4.5) |
| `app/build.gradle.kts` | `versionCode = 118`, `versionName = "1.1.8"` |

### 4.7 Invariantes que siguen vigentes

- El AFR se aplica **una sola vez por stream**; `attach()` cortocircuita con `currentAppliedModeId != 0` y
  `restoreDefault()` es lo único que libera el enclavamiento.
- **Nunca** leer `MediaFormat.KEY_FRAME_RATE` ni fiarse de `format.frameRate` en la banda 23.7..24.1.
- `versionName` sigue siendo SemVer puro (`1.1.8`, sin sufijos) para que `UpdateRepositoryImpl` lo compare.

