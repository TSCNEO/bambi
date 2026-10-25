package com.shieldstream.tv.media.mkv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * El [DefaultExtractorsFactory] de siempre, pero para MKV entrega [LenientMatroskaExtractor]: si una pista viene
 * comprimida con zlib (u otra codificación que Media3 no soporta), se ignora esa pista en vez de rechazar el archivo
 * entero (antes: ERROR_CODE_PARSING_CONTAINER_MALFORMED con "ContentCompAlgo 0 not supported").
 *
 * Lo usan el reproductor (Media3Factory) y las miniaturas (ThumbFrameExtractor): si no, con esos archivos el vídeo
 * se vería pero las miniaturas fallarían.
 */
@OptIn(UnstableApi::class)
class LenientExtractorsFactory : ExtractorsFactory {

    private val delegate = DefaultExtractorsFactory()

    // Mismos valores por defecto que DefaultExtractorsFactory; DefaultMediaSourceFactory los cambia con los setters.
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscodingEnabled = true

    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        textTrackTranscodingEnabled = enabled
        return this
    }

    @Synchronized
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(factory)
        subtitleParserFactory = factory
        return this
    }

    @Synchronized
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
        return this
    }

    @Synchronized
    override fun createExtractors(): Array<Extractor> = swapMatroska(delegate.createExtractors())

    @Synchronized
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swapMatroska(delegate.createExtractors(uri, responseHeaders))

    /** Mismo orden y mismos flags que DefaultExtractorsFactory (Media3 1.6.0); solo cambia la clase del de MKV. */
    private fun swapMatroska(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { i ->
        val e = extractors[i]
        if (e is MatroskaExtractor) {
            LenientMatroskaExtractor(
                subtitleParserFactory,
                if (textTrackTranscodingEnabled) 0 else LenientMatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            )
        } else {
            e
        }
    }
}
