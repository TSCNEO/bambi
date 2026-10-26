package com.shieldstream.tv.domain

/** Tramo del episodio en milisegundos. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    operator fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/**
 * Tramos de un episodio que se pueden saltar (de la base de datos pública IntroDB): resumen ("Anteriormente…"),
 * intro, créditos finales y escena poscréditos. null = no se sabe (o no tiene).
 */
data class SkipSegments(
    val intro: TimeRange? = null,
    val recap: TimeRange? = null,
    val outro: TimeRange? = null,
    val postCredits: TimeRange? = null,
) {
    companion object {
        val NONE = SkipSegments()
    }
}

interface SkipSegmentsRepository {
    /** Nunca falla: sin datos o sin red devuelve [SkipSegments.NONE]. Ids de IMDb (tt…). */
    suspend fun episode(imdbId: String, season: Int, episode: Int): SkipSegments
}
