package com.shieldstream.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.effect.SingleColorLut
import com.shieldstream.tv.player.thumbs.ThumbFrameExtractor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Separación entre miniaturas: cada ◀▶ (10 s) mueve la tira una posición. */
internal const val THUMB_SLOT_MS = 10_000L

/** Alto del fotograma extraído (px). 216 px basta para una tarjeta de ~140 dp y ocupa poco (≈330 KB). */
private const val THUMB_HEIGHT_PX = 216

/** Memoria máxima para miniaturas ya extraídas (≈70 fotogramas). */
private const val CACHE_BYTES = 24 * 1024 * 1024

/** Tras dejar de buscar, el extractor se mantiene este tiempo por si se vuelve a buscar enseguida. */
private const val IDLE_RELEASE_MS = 30_000L

/**
 * Miniaturas de búsqueda al estilo Netflix.
 *
 * Netflix las tiene pregeneradas en sus servidores; aquí no existen, así que se sacan al vuelo del MISMO archivo
 * de Real-Debrid con un segundo reproductor oculto (ExperimentalFrameExtractor de Media3):
 *  - CLOSEST_SYNC: salta al fotograma clave más cercano. Solo se descarga y decodifica ese fotograma, que es lo
 *    más rápido (la imagen puede ser de 1–3 s antes o después; para una miniatura da igual).
 *  - Presentation.createForHeight: el fotograma se reduce en la GPU antes de copiarlo a memoria.
 *  - HDR / HDR10: el extractor lo pasa a SDR (BT.709) para que la miniatura no salga lavada.
 *
 * HDR en la Shield (punto crítico): el driver del Tegra X1 no tiene GL_EXT_YUV_target, así que el pase HDR→SDR
 * de Media3 no funciona. Por eso se usa [ThumbFrameExtractor] (copia ajustada del de Media3), que trata la entrada
 * como SDR, y aquí se añade una tabla de color (LUT) que convierte los valores PQ (HDR10 / Dolby Vision 8) a SDR.
 * Dolby Vision: se decodifica con el decodificador HEVC normal (capa compatible HDR10 del perfil 8). El perfil 5
 * no tiene capa compatible: sin miniaturas.
 *
 * Ciclo de vida (puntos críticos):
 *  - Un único hilo: se crea y se usa SIEMPRE en el hilo principal (Media3 lo exige).
 *  - Mientras se busca, el reproductor principal está en pausa, así que el segundo decodificador no le quita
 *    nada a la película.
 *  - Se libera a los [IDLE_RELEASE_MS] de dejar de buscar, al cambiar de vídeo y al salir del reproductor, para
 *    no dejar un decodificador de vídeo ocupado.
 *  - Si el archivo no se puede decodificar así (p. ej. Dolby Vision perfil 5 o AV1 sin decodificador del
 *    sistema), [broken] pasa a true y la tira enseña solo los tiempos.
 */
@OptIn(UnstableApi::class)
internal class SeekThumbnails(context: Context, uri: Uri, pqHdr: Boolean) {

    private val extractor = ThumbFrameExtractor(
        context,
        ThumbFrameExtractor.Configuration.Builder()
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // Decodificador por hardware del Tegra (el de software de Android no admite HEVC de 10 bits), sin el
            // de Dolby Vision: así se usa la capa HEVC compatible.
            .setMediaCodecSelector(HardwareWithoutDolbyVision)
            .build(),
    )

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<Long>()
    private var consecutiveFailures = 0
    private var released = false

    // Cola propia en el hilo principal (Media3 exige un solo hilo). Vive con este objeto, no con la pantalla:
    // si se moviera con cada ◀▶ habría que cancelar peticiones a medias, y eso es lo que cerraba la app.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    /** Miniaturas que interesan ahora, por orden de importancia. La cola siempre lee la lista más reciente. */
    private var wanted: List<Long> = emptyList()

    /** Sube cada vez que llega una miniatura nueva: la tira se recompone. */
    var version by mutableIntStateOf(0)
        private set

    /** El archivo no da miniaturas (formato no soportado por el extractor): se enseñan solo los tiempos. */
    var broken by mutableStateOf(false)
        private set

    init {
        // Primero se reduce (GPU) y luego se aplica la LUT: así la LUT trabaja sobre la imagen pequeña.
        val effects = buildList<Effect> {
            add(Presentation.createForHeight(THUMB_HEIGHT_PX))
            if (pqHdr) add(PqToSdr.lut)
        }
        extractor.setMediaItem(MediaItem.fromUri(uri), effects)
    }

    fun get(slot: Long): Bitmap? = cache.get(slot)

    /**
     * Indica qué miniaturas hacen falta ahora (el centro primero). No espera: la cola las va sacando de una en una
     * y, al terminar cada una, elige la siguiente según la ÚLTIMA lista pedida. Así, aunque se pulse ◀▶ muy
     * deprisa, nunca se corta una extracción a medias y siempre se prioriza lo que está en pantalla.
     */
    fun want(slots: List<Long>) {
        wanted = slots
        if (worker?.isActive != true && !released && !broken) worker = scope.launch { work() }
    }

    private suspend fun work() {
        while (!released && !broken) {
            val slot = wanted.firstOrNull { cache.get(it) == null && it !in failed } ?: return
            val result = withTimeoutOrNull(FRAME_TIMEOUT_MS) { frameAt(slot * THUMB_SLOT_MS) }
            if (released) return
            when {
                result == null -> {
                    // Fotograma atascado (red muy lenta): el extractor sigue con él y las siguientes peticiones irían
                    // detrás. Se deja de pedir para este vídeo; la búsqueda funciona igual, con los tiempos.
                    Log.w(TAG, "Fotograma ${slot * THUMB_SLOT_MS} ms: sin respuesta en $FRAME_TIMEOUT_MS ms")
                    broken = true
                }
                result.isSuccess -> {
                    cache.put(slot, result.getOrThrow())
                    consecutiveFailures = 0
                    version++
                }
                else -> {
                    failed += slot
                    if (++consecutiveFailures >= MAX_FAILURES) {
                        Log.w(TAG, "Sin miniaturas para este archivo (fallan $MAX_FAILURES seguidas)")
                        broken = true
                    }
                }
            }
        }
    }

    /** La petición al extractor nunca se cancela (no lo soporta bien): si esta espera se corta, el resultado se ignora. */
    private suspend fun frameAt(positionMs: Long): Result<Bitmap> = suspendCancellableCoroutine { cont ->
        val future = extractor.getFrame(positionMs)
        future.addListener(
            {
                val result = runCatching { future.get().bitmap }
                    .onFailure { Log.w(TAG, "Fotograma $positionMs ms: ${it.cause?.message ?: it.message}") }
                if (cont.isActive) cont.resume(result)
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel()
        runCatching { extractor.release() }
        cache.evictAll()
    }

    private companion object {
        const val TAG = "SeekThumbs"
        const val FRAME_TIMEOUT_MS = 8_000L
        const val MAX_FAILURES = 3
    }
}

/** Decodificadores por hardware normales, pero sin el de Dolby Vision (ver [SeekThumbnails]). */
@OptIn(UnstableApi::class)
private val HardwareWithoutDolbyVision = MediaCodecSelector { mimeType, secure, tunneling ->
    // Dolby Vision → decoder HEVC directamente: Media3 solo busca la alternativa HEVC para los perfiles 4/8,
    // y los remux de Blu-ray (perfil 7) se quedaban sin miniaturas. El perfil 5 se descarta en PlayerScreen.
    if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, secure, tunneling)
    else MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
}

/**
 * Tabla de color (LUT 3D, 33³) de PQ / BT.2020 (HDR10) a SDR / BT.709 para las miniaturas:
 * PQ → nits → compresión de luces (Reinhard extendido: 100 nits ≈ blanco de papel, 1000 nits → blanco) →
 * gama BT.2020 → BT.709 → gamma 2.2. Se calcula una vez (≈36 000 colores, milisegundos).
 */
@OptIn(UnstableApi::class)
private object PqToSdr {
    private const val SIZE = 33
    private const val M1 = 0.1593017578125
    private const val M2 = 78.84375
    private const val C1 = 0.8359375
    private const val C2 = 18.8515625
    private const val C3 = 18.6875
    private const val REF_NITS = 100.0
    private const val WHITE = 1000.0 / REF_NITS

    val lut: SingleColorLut by lazy {
        val step = 1.0 / (SIZE - 1)
        SingleColorLut.createFromCube(
            Array(SIZE) { r -> Array(SIZE) { g -> IntArray(SIZE) { b -> map(r * step, g * step, b * step) } } },
        )
    }

    /** Señal PQ (0–1) → luminancia lineal en nits (SMPTE ST 2084). */
    private fun pqToNits(signal: Double): Double {
        val p = signal.coerceIn(0.0, 1.0).pow(1.0 / M2)
        return (max(p - C1, 0.0) / (C2 - C3 * p)).pow(1.0 / M1) * 10_000.0
    }

    private fun map(r: Double, g: Double, b: Double): Int {
        val rl = pqToNits(r)
        val gl = pqToNits(g)
        val bl = pqToNits(b)
        val y = 0.2627 * rl + 0.6780 * gl + 0.0593 * bl
        if (y <= 0.0) return android.graphics.Color.BLACK
        val x = y / REF_NITS
        val scale = (x * (1 + x / (WHITE * WHITE)) / (1 + x)) / x / REF_NITS
        val r2 = rl * scale
        val g2 = gl * scale
        val b2 = bl * scale
        fun out(v: Double) = (v.coerceIn(0.0, 1.0).pow(1 / 2.2) * 255).roundToInt()
        return android.graphics.Color.rgb(
            out(1.6605 * r2 - 0.5876 * g2 - 0.0728 * b2),
            out(-0.1246 * r2 + 1.1329 * g2 - 0.0083 * b2),
            out(-0.0182 * r2 - 0.1006 * g2 + 1.1187 * b2),
        )
    }
}

/**
 * Extractor de miniaturas para el vídeo actual. Se crea al empezar a buscar ([active]) y se libera
 * [IDLE_RELEASE_MS] después de terminar, al cambiar de vídeo o al salir de la pantalla.
 */
@Composable
internal fun rememberSeekThumbnails(uri: Uri?, pqHdr: Boolean, active: Boolean): SeekThumbnails? {
    val context = LocalContext.current
    var thumbs by remember(uri, pqHdr) { mutableStateOf<SeekThumbnails?>(null) }
    LaunchedEffect(uri, pqHdr, active) {
        if (uri == null) return@LaunchedEffect
        if (active) {
            if (thumbs == null) {
                thumbs = runCatching { SeekThumbnails(context, uri, pqHdr) }
                    .onFailure { Log.w("SeekThumbs", "No se pudo crear el extractor: ${it.message}") }
                    .getOrNull()
            }
        } else if (thumbs != null) {
            delay(IDLE_RELEASE_MS)
            thumbs?.release()
            thumbs = null
        }
    }
    DisposableEffect(uri, pqHdr) {
        onDispose {
            thumbs?.release()
            thumbs = null
        }
    }
    return thumbs
}

/**
 * Tira de 5 miniaturas sobre la barra de progreso: la del centro es el punto elegido (borde blanco y hora) y las
 * de los lados, ±10 s y ±20 s. Las de los extremos se salen de la pantalla, como en Netflix.
 */
@Composable
internal fun SeekThumbnailStrip(
    thumbs: SeekThumbnails?,
    targetMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val lastSlot = (durationMs / THUMB_SLOT_MS).coerceAtLeast(0L)
    val center = (targetMs / THUMB_SLOT_MS).coerceIn(0L, lastSlot)
    val slots = (-2..2).map { center + it }
    // Orden de carga: centro, luego los inmediatos y por último los extremos.
    LaunchedEffect(thumbs, center) {
        thumbs?.want(listOf(0, -1, 1, -2, 2).map { center + it }.filter { it in 0..lastSlot })
    }
    val version = thumbs?.version // lee el estado: recompone al llegar cada miniatura
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatTime(targetMs),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            // Fondo propio: la hora va sobre el vídeo y se perdía con títulos o escenas claras.
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(unbounded = true),
        ) {
            slots.forEach { slot ->
                val isCenter = slot == center
                ThumbCard(
                    bitmap = if (slot in 0..lastSlot) thumbs?.get(slot) else null,
                    label = if (slot in 0..lastSlot && !isCenter) formatTime(slot * THUMB_SLOT_MS) else null,
                    visible = slot in 0..lastSlot,
                    highlighted = isCenter,
                    loading = thumbs != null && thumbs.broken.not() && version != null,
                )
            }
        }
    }
}

@Composable
private fun ThumbCard(bitmap: Bitmap?, label: String?, visible: Boolean, highlighted: Boolean, loading: Boolean) {
    val width = if (highlighted) 256.dp else 216.dp
    val height = width * 9 / 16
    if (!visible) {
        Spacer(Modifier.size(width, height))
        return
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width, height)
            .clip(shape)
            .background(Color(0xFF15161E))
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) Color.White else Color.White.copy(alpha = 0.15f), shape),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (label != null) {
            Text(label, color = Color.White.copy(alpha = if (loading) 0.35f else 0.6f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
