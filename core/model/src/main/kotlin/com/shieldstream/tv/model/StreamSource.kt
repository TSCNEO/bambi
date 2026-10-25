package com.shieldstream.tv.model

/**
 * Fuente de streaming resuelta (torrent/debrid) para un título concreto.
 */
data class StreamSource(
    val id: String,
    val title: String,
    val infoHash: String,
    val fileIdx: Int?,
    val magnet: String,
    val quality: Quality,
    val hdrType: HdrType,
    val sizeBytes: Long,
    val seeders: Int?,
    val isCached: Boolean,
    val directUrl: String? = null,
    /** Nombre del archivo dentro del torrent (behaviorHints.filename). Clave en packs de temporada. */
    val fileName: String? = null,
    /** Idiomas de audio que anuncia Torrentio con banderas (ES, LAT, EN…). Vacío = no indicado. */
    val languages: List<String> = emptyList(),
    /** AV1: la Shield lo decodifica por CPU (libgav1). Va al final de la cola de reproducción. */
    val softwareDecode: Boolean = false,
    /** Debrid vía Torrentio: enlace /resolve/ que se convierte en el enlace directo al reproducir. */
    val resolveUrl: String? = null,
    /** Nombre del servicio debrid si no es Real-Debrid ("TorBox"…), para los textos. */
    val debrid: String? = null,
    /** Buscador que la encontró (sección en "Fuentes"). */
    val provider: String = "",
    /** Serie: temporada y episodio buscados (para elegir el archivo dentro de un pack de temporada). */
    val season: Int? = null,
    val episode: Int? = null,
) {
    val displayLabel: String
        get() = buildString {
            append(quality.label)
            if (hdrType != HdrType.SDR) append(" · ").append(hdrType.label)
        }

    val isHdr: Boolean get() = hdrType != HdrType.SDR
}
