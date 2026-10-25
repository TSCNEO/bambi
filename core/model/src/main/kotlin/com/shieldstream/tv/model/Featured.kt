package com.shieldstream.tv.model

/** Por qué sale un título en la portada grande. */
enum class FeaturedBadge(val label: String) {
    NEW(tr("Recién llegada", "New arrival")),
    POPULAR(tr("La más popular", "Most popular")),
    SERIES_NOW(tr("La serie del momento", "Show of the moment")),
    MOVIE_NOW(tr("La película del momento", "Movie of the moment")),
    RISING(tr("Arrasa esta semana", "Trending this week")),
    ACCLAIMED(tr("Aclamada por la crítica", "Critically acclaimed")),
}

/**
 * Destacado de la portada grande del Inicio. Se eligen una vez por semana (ver CatalogRepository.getFeatured).
 * [durationText]: "1 h 37 min" (película) o "3 temporadas" (serie). [certification]: "16+", "TP"…
 */
data class FeaturedItem(
    val item: CatalogItem,
    val badge: FeaturedBadge,
    val logoUrl: String?,
    val genre: String?,
    val durationText: String?,
    val certification: String?,
)

/** Botones de categoría bajo la portada (en bucle). Cada uno abre su pantalla con filas. */
enum class Category(val title: String, val forMovies: Boolean = true, val forTv: Boolean = true) {
    DRAMAS("Dramas"),
    THRILLERS("Thrillers"),
    MOODS(tr("Estados de ánimo", "Moods")),
    CRIME(tr("Policiacas", "Crime")),
    ACTION(tr("Acción", "Action")),
    BOOKS(tr("Adaptaciones de libros", "Based on books")),
    COMEDY(tr("Comedias", "Comedies")),
    HALLOWEEN("Halloween"),
    REALITY(tr("Realities", "Reality TV"), forMovies = false),
    FANTASY(tr("Fantásticas", "Fantasy")),
    SCIFI(tr("Ciencia ficción", "Sci-fi")),
    ANIMATION(tr("Animación", "Animation")),
    ROMANCE(tr("Románticas", "Romance")),
    FAMILY(tr("Para toda la familia", "Family")),
    ANIME("Anime"),
    DOCUMENTARY(tr("Documentales", "Documentaries")),
    ASTROLOGY(tr("Astrología", "Astrology")),
    STANDUP("Stand-up"),
    ;

    companion object {
        fun forFilter(filter: HomeFilter): List<Category> = entries.filter {
            when (filter) {
                HomeFilter.ALL -> true
                HomeFilter.MOVIES -> it.forMovies
                HomeFilter.TV -> it.forTv
            }
        }
    }
}
