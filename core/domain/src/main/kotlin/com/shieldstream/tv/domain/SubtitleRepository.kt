package com.shieldstream.tv.domain

import com.shieldstream.tv.model.ExternalSubtitle
import com.shieldstream.tv.model.SubtitleFile
import com.shieldstream.tv.model.SubtitleQuery

/**
 * Subtítulos externos (OpenSubtitles + proveedores tipo a4kSubtitles).
 */
interface SubtitleRepository {
    /** Busca en todos los proveedores en paralelo; los que fallan o tardan se ignoran. */
    suspend fun search(query: SubtitleQuery): List<ExternalSubtitle>

    /** Descarga, descomprime (zip/gzip), convierte a UTF-8 y guarda en caché. */
    suspend fun download(subtitle: ExternalSubtitle, query: SubtitleQuery): SubtitleFile
}
