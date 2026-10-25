package com.shieldstream.tv.media.av1

import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer

/** libdav1dJNI.so (jniLibs de este módulo, compilada con _rebuild_av1.sh). */
internal object Dav1dLibrary {
    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("dav1dJNI") }
            .onFailure { Log.w("Dav1d", "libdav1dJNI no disponible: ${it.message}") }
            .isSuccess
    }
}

class Dav1dDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/**
 * Decoder AV1 por CPU con dav1d (el de VLC/Kodi). Mismo esquema que el Gav1Decoder de Media3, salvo que
 * dav1d decodifica varios fotogramas a la vez: las imágenes salen con unos fotogramas de retraso y cada
 * una lleva su propio timestamp (no el del paquete que acaba de entrar).
 *
 * Ciclo de vida de una imagen: nativeDequeue la aparca en un slot nativo → el renderer la pinta
 * (renderToSurface) → releaseOutputBuffer libera el slot. Tras release() el contexto nativo ya no existe:
 * todo acceso pasa por [nativeLock] y comprueba [closed].
 */
@Suppress("UNCHECKED_CAST")
@OptIn(UnstableApi::class)
internal class Dav1dDecoder(
    numInputBuffers: Int,
    numOutputBuffers: Int,
    initialInputBufferSize: Int,
    threads: Int,
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, Dav1dDecoderException>(
    // Arrays vacíos: el constructor de SimpleDecoder los rellena con createInputBuffer/createOutputBuffer.
    arrayOfNulls<DecoderInputBuffer>(numInputBuffers) as Array<DecoderInputBuffer>,
    arrayOfNulls<VideoDecoderOutputBuffer>(numOutputBuffers) as Array<VideoDecoderOutputBuffer>,
) {
    private val nativeLock = Any()
    private val context: Long
    private var closed = false

    @Volatile
    private var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE

    /** {slot, timeUs, ancho, alto}. Solo lo usa el hilo del decoder. */
    private val frameInfo = LongArray(4)

    init {
        if (!Dav1dLibrary.isAvailable) throw Dav1dDecoderException("libdav1dJNI no cargada")
        context = nativeOpen(threads, MAX_FRAME_DELAY)
        if (context == 0L) throw Dav1dDecoderException("dav1d_open falló")
        setInitialInputBufferSize(initialInputBufferSize)
    }

    override fun getName(): String = "dav1d"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    // Se llama desde el constructor de SimpleDecoder: no usar aquí propiedades de esta clase.
    override fun createOutputBuffer(): VideoDecoderOutputBuffer =
        VideoDecoderOutputBuffer { releaseOutputBuffer(it) }.apply { decoderPrivate = NO_SLOT }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean,
    ): Dav1dDecoderException? {
        if (reset) nativeFlush(context) // Seek: se tiran los fotogramas a medio decodificar.
        outputBuffer.decoderPrivate = NO_SLOT
        val data: ByteBuffer = inputBuffer.data ?: return Dav1dDecoderException("Paquete vacío")
        val sent = nativeDecode(context, data, data.limit(), inputBuffer.timeUs)
        if (sent < 0) return Dav1dDecoderException("dav1d_send_data: error $sent")

        val got = nativeDequeue(context, frameInfo)
        if (got < 0) return Dav1dDecoderException("dav1d_get_picture: error $got")
        if (got == 0) {
            // Aún en vuelo (frame threading): este paquete no produce imagen todavía.
            outputBuffer.shouldBeSkipped = true
            return null
        }
        val slot = frameInfo[0].toInt()
        val timeUs = frameInfo[1]
        // SimpleDecoder decide "saltar" por el timestamp del paquete; aquí manda el de la imagen.
        if (!isAtLeastOutputStartTimeUs(timeUs)) {
            nativeRelease(context, slot)
            outputBuffer.shouldBeSkipped = true
            return null
        }
        outputBuffer.shouldBeSkipped = false
        outputBuffer.init(timeUs, outputMode, /* supplementalData= */ null)
        outputBuffer.initForPrivateFrame(frameInfo[2].toInt(), frameInfo[3].toInt())
        outputBuffer.decoderPrivate = slot
        outputBuffer.format = inputBuffer.format
        return null
    }

    override fun createUnexpectedDecodeException(error: Throwable): Dav1dDecoderException =
        Dav1dDecoderException("Error inesperado en dav1d", error)

    override fun releaseOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        synchronized(nativeLock) {
            if (outputBuffer.decoderPrivate != NO_SLOT && !closed) {
                nativeRelease(context, outputBuffer.decoderPrivate)
            }
            outputBuffer.decoderPrivate = NO_SLOT
        }
        super.releaseOutputBuffer(outputBuffer)
    }

    override fun release() {
        super.release() // Espera a que termine el hilo del decoder.
        synchronized(nativeLock) {
            if (!closed) nativeClose(context)
            closed = true
        }
    }

    fun setOutputMode(outputMode: Int) {
        this.outputMode = outputMode
    }

    /** Hilo de reproducción. Solo en modo Surface (SurfaceView del PlayerView). */
    fun renderToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
            throw Dav1dDecoderException("Modo de salida no soportado: ${outputBuffer.mode}")
        }
        val result = synchronized(nativeLock) {
            if (closed) 0 else nativeRender(context, outputBuffer.decoderPrivate, surface)
        }
        if (result < 0) throw Dav1dDecoderException("Error al pintar el fotograma ($result)")
    }

    private external fun nativeOpen(threads: Int, maxFrameDelay: Int): Long
    private external fun nativeClose(context: Long)
    private external fun nativeFlush(context: Long)
    private external fun nativeDecode(context: Long, data: ByteBuffer, size: Int, timeUs: Long): Int
    private external fun nativeDequeue(context: Long, info: LongArray): Int
    private external fun nativeRelease(context: Long, slot: Int)
    private external fun nativeRender(context: Long, slot: Int, surface: Surface): Int

    private companion object {
        const val NO_SLOT = -1
        /**
         * Fotogramas decodificados en paralelo. 0 = automático (2 con 4 núcleos). Probado 4 con un 4K:
         * usa los 4 núcleos al 100 % y +150 MB de RAM, y sigue en ~7 fps → no compensa.
         */
        const val MAX_FRAME_DELAY = 0
    }
}
