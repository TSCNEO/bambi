package com.shieldstream.tv.media

import android.app.Activity
import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gestión de Auto Frame Rate (AFR) en Android TV.
 *
 * En Android 11+, Media3/ExoPlayer llama internamente a `Surface.setFrameRate(fps, 1)` con la estrategia
 * por defecto `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`. Si el televisor conectado no dispone de HDMI 2.1 QMS
 * (Quick Media Switching), el sistema operativo ignora la petición y mantiene la salida a 60 Hz fijada en el
 * launcher, provocando judder (tirones de 3:2 pulldown) en películas a 23.976 / 24 fps.
 *
 * Esta clase detecta la tasa real del vídeo (a través de los metadatos de contenedor/pistas, o mediante
 * medición directa de timestamps PTS vía [VideoFrameMetadataListener] para archivos MKV/remuxes sin encabezado)
 * y activa el modo de pantalla nativo correspondiente mediante `preferredDisplayModeId` en la [Activity],
 * restaurando el modo original al finalizar la reproducción.
 */
@Singleton
@UnstableApi
class AutoFrameRateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var currentAppliedModeId: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeListener: AfrVideoFrameListener? = null
    private var activePlayer: Player? = null

    /**
     * Vincula el gestor de AFR al [player] y a la ventana de [activity].
     * Aplica la tasa inmediatamente si el contenedor la expone, o escucha los primeros fotogramas decodificados.
     *
     * Guarda *single-shot*: si ya hay un modo aplicado ([currentAppliedModeId] != 0) para este stream,
     * no se recalcula ni se vuelve a conmutar. Evita micro-conmutaciones (y parpadeos HDMI) por eventos
     * repetidos de pistas, cambios de tamaño de vídeo o reapertura del panel de pistas.
     *
     * Nota sobre la banda 23.9..24.1 (v1.1.8): aunque el contenedor exponga `frameRate`, si el valor cae en
     * esa banda NO se usa. Muchas cabeceras y decodificadores redondean 23.976 a 24.000, y aceptar ese
     * valor dejaría la TV en el modo 24.000 Hz de forma permanente con judder residual (1 fotograma cada
     * ~41 s). En esa banda manda siempre el muestreo de PTS reales.
     */
    fun attach(activity: Activity, player: Player) {
        if (currentAppliedModeId != 0) {
            Log.i(TAG, "AFR: modo ya enclavado (modeId=$currentAppliedModeId), se ignora nuevo attach()")
            return
        }
        detach(activity)
        activePlayer = player

        // 1. Detección directa por metadatos del contenedor / pistas (solo si la tasa no es ambigua)
        val directFps = extractFrameRate(player)
        if (directFps > 0f && !isAmbiguousRate(directFps)) {
            Log.i(TAG, "AFR: detectado frame rate directo en contenedor/pistas: $directFps fps")
            applyFrameRate(activity, directFps)
            return
        }
        if (directFps > 0f) {
            Log.i(TAG, "AFR: frame rate del contenedor $directFps fps en banda ambigua 23.9..24.1: se ignora y se mide por PTS")
        }

        // 2. Detección en tiempo real vía VideoFrameMetadataListener en ExoPlayer (ideal para MKVs sin encabezado de fps)
        if (player is ExoPlayer) {
            Log.i(TAG, "AFR: registrando VideoFrameMetadataListener en ExoPlayer para muestreo PTS en vivo")
            val listener = AfrVideoFrameListener(activity, player)
            activeListener = listener
            player.setVideoFrameMetadataListener(listener)
        }
    }

    /**
     * Desvincula cualquier listener activo de AFR.
     */
    fun detach(activity: Activity) {
        activeListener?.let { l ->
            (activePlayer as? ExoPlayer)?.clearVideoFrameMetadataListener(l)
        }
        activeListener = null
        activePlayer = null
    }

    /**
     * Compatibilidad: vincula y aplica AFR para el [player].
     */
    fun applyForPlayer(activity: Activity, player: Player) {
        attach(activity, player)
    }

    /**
     * Aplica la tasa de refresco óptima en la ventana de [activity] para los [fps] del vídeo.
     */
    fun applyFrameRate(activity: Activity, fps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (fps <= 0f || fps.isNaN() || fps.isInfinite()) return

        val window = activity.window ?: return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: window.decorView.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        } ?: return

        val activeMode = display.mode ?: return
        val supportedModes = display.supportedModes ?: return
        if (supportedModes.size <= 1) return

        val targetMode = findBestDisplayMode(activeMode, supportedModes, fps) ?: return
        if (targetMode.modeId == currentAppliedModeId || targetMode.modeId == activeMode.modeId) {
            currentAppliedModeId = targetMode.modeId
            return
        }

        Log.i(
            TAG,
            "AFR: cambiando modo HDMI a ${targetMode.refreshRate} Hz (${targetMode.physicalWidth}x${targetMode.physicalHeight}, modeId=${targetMode.modeId}) para video de $fps fps (modo anterior: ${activeMode.refreshRate} Hz)",
        )

        val lp = window.attributes
        lp.preferredDisplayModeId = targetMode.modeId
        window.attributes = lp
        currentAppliedModeId = targetMode.modeId
    }

    /**
     * Restaura el modo de refresco por defecto (0 en WindowManager.LayoutParams para usar la configuración del sistema).
     * Desengancha también cualquier listener de AFR pendiente y libera el enclavamiento, de modo que la siguiente
     * reproducción pueda volver a conmutar.
     */
    fun restoreDefault(activity: Activity) {
        detach(activity)
        currentAppliedModeId = 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val window = activity.window ?: return
        val lp = window.attributes
        if (lp.preferredDisplayModeId != 0) {
            Log.i(TAG, "AFR: restaurando modo HDMI por defecto (preferredDisplayModeId = 0)")
            lp.preferredDisplayModeId = 0
            window.attributes = lp
        }
    }

    /**
     * Extrae los fotogramas por segundo del [player], comprobando tanto [Player.getVideoFormat] como
     * los formatos de las pistas de vídeo en `currentTracks`.
     */
    fun extractFrameRate(player: Player): Float {
        if (player is ExoPlayer) {
            val direct = player.videoFormat?.frameRate
            if (direct != null && direct > 0f) return direct
        }

        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                if (f.frameRate > 0f) return f.frameRate
            }
        }
        return Format.NO_VALUE.toFloat()
    }

    /**
     * Encuentra el mejor [Display.Mode] para la resolución actual y la tasa de fotogramas del vídeo.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    internal fun findBestDisplayMode(
        activeMode: Display.Mode,
        supportedModes: Array<Display.Mode>,
        videoFps: Float,
    ): Display.Mode? {
        // Filtrar modos con la misma resolución física del display activo
        val sameResModes = supportedModes.filter {
            it.physicalWidth == activeMode.physicalWidth && it.physicalHeight == activeMode.physicalHeight
        }
        if (sameResModes.isEmpty()) return null

        val normalizedFps = normalizeFps(videoFps)

        // 1. Coincidencia exacta de frecuencia (tolerancia < 0.02 Hz).
        //    Se elige el modo MÁS CERCANO y, ante empate técnico, se prioriza 23.976 sobre 24.000 porque
        //    ~99% del cine comercial está masterizado en 23.976p (24000/1001) y un panel que soporta ambos
        //    modos debe quedarse en el 23.976025 Hz (Modo 149 en Homatics) para no dejar judder residual.
        val exact = sameResModes
            .filter { abs(it.refreshRate - normalizedFps) < 0.02f }
            .minWithOrNull(
                compareBy<Display.Mode> { abs(it.refreshRate - normalizedFps) }
                    .thenBy { if (isNearly24000(it.refreshRate)) 1 else 0 },
            )
        if (exact != null) return exact

        // 2. Múltiplo entero suave (ej. 25 fps -> 50 Hz, 23.976 -> 119.88 Hz, 29.97 -> 59.94 Hz, 30 -> 60 Hz)
        val multiples = sameResModes.mapNotNull { mode ->
            val ratio = mode.refreshRate / normalizedFps
            val nearestInt = ratio.roundToInt()
            if (nearestInt in 2..5 && abs(mode.refreshRate - (nearestInt * normalizedFps)) < 0.05f) {
                nearestInt to mode
            } else null
        }.sortedBy { it.first }

        if (multiples.isNotEmpty()) {
            return multiples.first().second
        }

        // 3. Coincidencia cercana (tolerancia < 0.1 Hz, ej. 23.976 en una tele que solo tiene modo 24.000)
        sameResModes.firstOrNull { abs(it.refreshRate - normalizedFps) < 0.1f }?.let { return it }

        return null
    }

    private fun normalizeFps(fps: Float): Float = when {
        // Banda 23.976 vs 24.000: la frontera se pone en 23.99. El muestreo PTS de un máster 24000/1001
        // (delta 41708 µs) da 23.9755..23.9765 y cae a la izquierda (Modo 149, 23.976025 Hz); el de un
        // máster 24.000 (delta 41666 µs) da 24.0000 y cae a la derecha (Modo 150, 24.000002 Hz).
        fps in 23.70f..23.99f -> 23.976025f
        fps in 23.99f..24.10f -> 24.0f
        fps in 24.90f..25.05f -> 25.0f
        fps in 29.90f..29.985f -> 29.97003f
        fps in 29.985f..30.05f -> 30.0f
        fps in 49.90f..50.05f -> 50.0f
        fps in 59.85f..59.97f -> 59.94006f
        fps in 59.97f..60.10f -> 60.0f
        else -> fps
    }

    /** ¿La tasa cae en la banda ambigua donde 23.976 y 24.000 son indistinguibles sin medir PTS? */
    private fun isAmbiguousRate(fps: Float): Boolean = fps in 23.70f..24.10f

    /** ¿El modo es el de 24.000 Hz (frente al de 23.976 Hz)? Se usa solo como desempate. */
    private fun isNearly24000(refreshRate: Float): Boolean = abs(refreshRate - 24.0f) < abs(refreshRate - 23.976025f)

    private inner class AfrVideoFrameListener(
        private val activity: Activity,
        private val exoPlayer: ExoPlayer,
    ) : VideoFrameMetadataListener {
        private val ptsList = Collections.synchronizedList(mutableListOf<Long>())
        @Volatile private var completed = false

        override fun onVideoFrameAboutToBeRendered(
            presentationTimeUs: Long,
            releaseTimeNs: Long,
            format: Format,
            mediaFormat: MediaFormat?,
        ) {
            if (completed) return

            // `format.frameRate` solo se acepta si es concluyente (fuera de la banda 23.9..24.1).
            // En MKV/remux vale Format.NO_VALUE; en MP4 suele venir redondeado a 24.0 aunque el máster
            // sea 23.976, y en ese caso la única fuente de verdad son los PTS de los fotogramas.
            //
            // NO se lee `mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE)`: el decodificador hardware de
            // Amlogic (Homatics / Xiaomi) devuelve el entero 24 aunque el vídeo sea 23.976, y aceptarlo
            // conmutaba la TV al modo 24.000 Hz con judder residual permanente.
            if (format.frameRate > 0f && !isAmbiguousRate(format.frameRate)) {
                completed = true
                dispatchApply(activity, format.frameRate)
                return
            }

            if (presentationTimeUs < 0) return
            ptsList.add(presentationTimeUs)

            if (ptsList.size >= SAMPLE_FRAMES) {
                completed = true
                val measured = measureFpsFromPts(ptsList.toList())
                if (measured != null) {
                    Log.i(TAG, "AFR: fps reales por muestreo PTS: $measured fps")
                    dispatchApply(activity, measured)
                } else {
                    completed = false // Deltas inservibles (seek, primer keyframe tras buffering): seguir muestreando
                }
            }
        }

        /**
         * Calcula los fps reales a partir de los `presentationTimeUs` de los primeros fotogramas.
         *
         * Se toman las diferencias entre fotogramas consecutivos (no hace falta ordenar: el decoder los
         * entrega en orden de presentación), se descartan las que caen fuera de 10..200 fps y las que se
         * desvían más de un 25 % de la mediana — así un fotograma perdido (delta doble ~83 ms) no arrastra
         * la media y hace que 23.976 se calcule como 17.9 fps. Devuelve `null` si no hay muestra suficiente.
         */
        private fun measureFpsFromPts(pts: List<Long>): Float? {
            val deltas = buildList {
                for (i in 1 until pts.size) {
                    val delta = pts[i] - pts[i - 1]
                    if (delta in 5_000L..100_000L) add(delta)
                }
            }
            if (deltas.size < SAMPLE_FRAMES - 3) return null

            val median = deltas.sorted()[deltas.size / 2]
            val clean = deltas.filter { abs(it - median) <= median * 0.25f }
            if (clean.size < SAMPLE_FRAMES - 3) return null

            val avgDeltaUs = clean.sum().toDouble() / clean.size
            if (avgDeltaUs <= 0.0) return null
            Log.i(
                TAG,
                "AFR: ${clean.size} deltas PTS válidos, media ${"%.3f".format(avgDeltaUs)} us " +
                    "(mediana $median us) -> ${"%.4f".format(1_000_000.0 / avgDeltaUs)} fps",
            )
            return (1_000_000.0 / avgDeltaUs).toFloat()
        }

        private fun dispatchApply(act: Activity, fps: Float) {
            // Limpieza inmediata del listener *antes* de conmutar el modo: el callback llega desde el hilo de
            // render y si se dejara registrado seguiría muestreando fotogramas y podría re-aplicar el modo.
            runCatching { exoPlayer.clearVideoFrameMetadataListener(this) }
            if (activeListener === this) {
                activeListener = null
            }
            mainHandler.post {
                applyFrameRate(act, fps)
            }
        }
    }

    companion object {
        private const val TAG = "AutoFrameRate"

        /** Fotogramas muestreados antes de decidir la tasa (≈0.5 s a 23.976 fps). */
        private const val SAMPLE_FRAMES = 12
    }
}
