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
     */
    fun attach(activity: Activity, player: Player) {
        detach(activity)
        activePlayer = player

        // 1. Detección directa por metadatos del contenedor / pistas
        val directFps = extractFrameRate(player)
        if (directFps > 0f) {
            Log.i(TAG, "AFR: detectado frame rate directo en contenedor/pistas: $directFps fps")
            applyFrameRate(activity, directFps)
            return
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
     */
    fun restoreDefault(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val window = activity.window ?: return
        val lp = window.attributes
        if (lp.preferredDisplayModeId != 0 || currentAppliedModeId != 0) {
            Log.i(TAG, "AFR: restaurando modo HDMI por defecto (preferredDisplayModeId = 0)")
            lp.preferredDisplayModeId = 0
            window.attributes = lp
            currentAppliedModeId = 0
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

        // 1. Coincidencia exacta de frecuencia (tolerancia < 0.02 Hz)
        sameResModes.firstOrNull { abs(it.refreshRate - normalizedFps) < 0.02f }?.let { return it }

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
        fps in 23.75f..23.99f -> 23.976025f
        fps in 23.99f..24.05f -> 24.0f
        fps in 24.9f..25.05f -> 25.0f
        fps in 29.9f..29.985f -> 29.97003f
        fps in 29.985f..30.05f -> 30.0f
        fps in 49.9f..50.05f -> 50.0f
        fps in 59.85f..59.97f -> 59.94006f
        fps in 59.97f..60.1f -> 60.0f
        else -> fps
    }

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

            // Si format reporta frameRate válido
            if (format.frameRate > 0f) {
                completed = true
                dispatchApply(activity, format.frameRate)
                return
            }

            // Si mediaFormat del decodificador hardware reporta KEY_FRAME_RATE
            if (mediaFormat != null) {
                val mfRate = runCatching {
                    if (mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE)
                    } else null
                }.getOrNull()
                if (mfRate != null && mfRate > 0f) {
                    completed = true
                    dispatchApply(activity, mfRate)
                    return
                }
            }

            if (presentationTimeUs < 0) return
            ptsList.add(presentationTimeUs)

            if (ptsList.size >= 12) {
                completed = true
                val sorted = ptsList.toList().sorted()
                var totalDurationUs = 0L
                var validDeltas = 0
                for (i in 1 until sorted.size) {
                    val delta = sorted[i] - sorted[i - 1]
                    if (delta in 5_000L..100_000L) { // rango 10 fps a 200 fps
                        totalDurationUs += delta
                        validDeltas++
                    }
                }
                if (validDeltas >= 6) {
                    val avgDurationUs = totalDurationUs.toFloat() / validDeltas
                    val calculatedFps = 1_000_000f / avgDurationUs
                    Log.i(TAG, "AFR: calculado por PTS en vivo: $calculatedFps fps (media: $avgDurationUs us)")
                    dispatchApply(activity, calculatedFps)
                } else {
                    completed = false // Pocos deltas válidos, tomar más muestras
                }
            }
        }

        private fun dispatchApply(act: Activity, fps: Float) {
            mainHandler.post {
                applyFrameRate(act, fps)
                exoPlayer.clearVideoFrameMetadataListener(this)
                if (activeListener === this) {
                    activeListener = null
                }
            }
        }
    }

    companion object {
        private const val TAG = "AutoFrameRate"
    }
}
