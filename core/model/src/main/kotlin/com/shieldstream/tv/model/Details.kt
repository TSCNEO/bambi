package com.shieldstream.tv.model

/** Ficha completa de un título para la pantalla de detalle. */
data class CatalogDetails(
    val item: CatalogItem,
    val originalTitle: String? = null,
    val logoUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val tagline: String? = null,
    val seasons: List<Season> = emptyList(),
)

data class Season(
    val number: Int,
    val name: String,
    val episodeCount: Int,
)

data class Episode(
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String,
    val stillUrl: String?,
    val runtimeMinutes: Int?,
) {
    val code: String get() = tr("T$season:E$number", "S$season:E$number")
}

/** Filtro de la Home: todo, solo películas o solo series. */
enum class HomeFilter { ALL, MOVIES, TV }

/** Estilo visual de una fila de la Home. */
enum class RowStyle { POSTER, TOP10, CONTINUE }
