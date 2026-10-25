package com.shieldstream.tv.model

/** Qué se está reproduciendo (para buscar subtítulos externos). */
data class PlaybackMedia(
    val type: MediaType,
    val imdbId: String?,
    val title: String,
    /** Título original (TMDB): los buscadores de subtítulos casi siempre indexan por el inglés/original. */
    val originalTitle: String?,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null,
)

/** Búsqueda de subtítulos externos. [fileName] = nombre del archivo que se reproduce (para afinar la sincronía). */
data class SubtitleQuery(
    val media: PlaybackMedia,
    val language: String,
    val fileName: String?,
)

/** Resultado de un proveedor externo (OpenSubtitles, Podnapisi, SubDL…). */
data class ExternalSubtitle(
    val provider: String,
    val id: String,
    /** Nombre de la release del subtítulo (vacío si el proveedor no lo da). */
    val release: String,
    /** ISO 639-1 ("es", "en"…). */
    val language: String,
    val hearingImpaired: Boolean,
    val downloadUrl: String,
    /** 0..1: parecido entre la release del subtítulo y el archivo que se reproduce (sincronía probable). */
    val matchScore: Float = 0f,
)

/** Subtítulo descargado, convertido a UTF-8 y listo para ExoPlayer. */
data class SubtitleFile(
    val path: String,
    val mimeType: String,
    val language: String,
    val label: String,
)

/** Idiomas ofrecidos en "Subtítulos externos". */
object SubtitleLanguages {
    val all: List<Pair<String, String>> = listOf(
        "es" to tr("Español", "Spanish"),
        "en" to tr("Inglés", "English"),
        "ca" to tr("Catalán", "Catalan"),
        "fr" to tr("Francés", "French"),
        "it" to tr("Italiano", "Italian"),
        "pt" to tr("Portugués", "Portuguese"),
        "de" to tr("Alemán", "German"),
    )

    fun label(code: String): String = all.firstOrNull { it.first == code }?.second ?: code.uppercase()
}
