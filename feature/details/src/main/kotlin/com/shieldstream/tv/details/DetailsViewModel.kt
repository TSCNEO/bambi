package com.shieldstream.tv.details

import com.shieldstream.tv.model.tr
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.PrioritizeSourcesUseCase
import com.shieldstream.tv.domain.RealDebridRepository
import com.shieldstream.tv.domain.SelectedContentHolder
import com.shieldstream.tv.domain.WatchProgressRepository
import com.shieldstream.tv.media.DisplayCapabilityDetector
import com.shieldstream.tv.model.CatalogDetails
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.PlaybackMedia
import com.shieldstream.tv.model.StreamSource
import com.shieldstream.tv.model.WatchProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject

sealed interface SourcesState {
    data object Idle : SourcesState
    data object Loading : SourcesState
    data class Ready(
        val sources: List<StreamSource>,
        val unavailable: Map<String, String> = emptyMap(),
        /** Buscadores que aún no han respondido (las fuentes van apareciendo según llegan). */
        val pending: Set<String> = emptySet(),
    ) : SourcesState
    data class Error(val message: String) : SourcesState
}

sealed interface EpisodesState {
    data object Loading : EpisodesState
    data class Ready(val episodes: List<Episode>) : EpisodesState
    data class Error(val message: String) : EpisodesState
}

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    data class Error(val message: String) : DetailsUiState
    data class Content(
        val details: CatalogDetails,
        val sources: SourcesState = SourcesState.Idle,
        val selectedSeason: Int? = null,
        val episodes: EpisodesState = EpisodesState.Loading,
        /** Episodio al que apunta "Reproducir/Reanudar" y al que se refieren [sources] (series). */
        val selectedEpisode: Episode? = null,
        /** Se pulsó Reproducir mientras cargaban las fuentes: arrancar en cuanto estén. */
        val pendingPlay: Boolean = false,
        val message: String? = null,
        /** Progreso de visionado guardado (todas las claves); se actualiza solo al volver del reproductor. */
        val progress: Map<String, WatchProgress> = emptyMap(),
    ) : DetailsUiState {
        val isSeries: Boolean get() = details.item.type == MediaType.TV

        fun progressOf(episode: Episode): WatchProgress? =
            progress[WatchProgress.episodeKey(details.item.id, episode.season, episode.number)]

        /** Progreso de lo que reproduciría el botón principal (película o episodio seleccionado). */
        val mainProgress: WatchProgress?
            get() = if (isSeries) selectedEpisode?.let { progressOf(it) } else progress[WatchProgress.movieKey(details.item.id)]
    }
}

/** Eventos de una sola vez hacia la pantalla. */
sealed interface DetailsEvent {
    data object OpenPlayer : DetailsEvent
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val realDebridRepository: RealDebridRepository,
    private val prioritizeSourcesUseCase: PrioritizeSourcesUseCase,
    private val displayCapabilities: DisplayCapabilityDetector,
    private val holder: SelectedContentHolder,
    private val watchProgress: WatchProgressRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading)
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    /**
     * Eventos de un solo uso hacia la pantalla. SIN búfer para quien no escucha: si la ficha no está en
     * primer plano, "abrir reproductor" se descarta. Antes se guardaba y saltaba más tarde, abriendo el
     * reproductor de otro título encima de lo que se estuviera viendo.
     */
    private val _events = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private var sourcesJob: Job? = null
    private var episodesJob: Job? = null
    private var pendingFromStart = false

    init {
        load()
        // Al volver del reproductor el progreso cambia: ticks, barras y "Reanudar" se actualizan solos.
        viewModelScope.launch {
            watchProgress.progress.collect { map -> updateContent { c -> withProgress(c, map) } }
        }
    }

    /**
     * Aplica el progreso nuevo. Si el episodio seleccionado acaba de quedar visto (vuelves del
     * reproductor tras terminarlo), "Reproducir" pasa al siguiente episodio, como en Netflix.
     */
    private fun withProgress(c: DetailsUiState.Content, map: Map<String, WatchProgress>): DetailsUiState.Content {
        val updated = c.copy(progress = map)
        val current = updated.selectedEpisode?.takeIf { updated.isSeries } ?: return updated
        if (updated.progressOf(current)?.isWatched != true) return updated
        val episodes = (updated.episodes as? EpisodesState.Ready)?.episodes ?: return updated
        val next = episodes.getOrNull(episodes.indexOf(current) + 1) ?: return updated
        return updated.copy(selectedEpisode = next, sources = SourcesState.Idle)
    }

    private fun load() {
        val item = holder.selectedItem.value
        if (item == null) {
            _uiState.value = DetailsUiState.Error(tr("No hay título seleccionado", "No title selected"))
            return
        }
        viewModelScope.launch {
            // Si TMDB falla seguimos con los datos básicos de la tarjeta.
            val details = runCatching { catalogRepository.getDetails(item) }.getOrElse { CatalogDetails(item) }
            _uiState.value = DetailsUiState.Content(details, progress = watchProgress.progress.value)
            if (details.item.type == MediaType.TV) {
                val (season, episode) = continueTarget(details)
                    ?: (details.seasons.firstOrNull()?.number to null)
                if (season != null) {
                    selectSeason(season, preferredEpisode = episode)
                } else {
                    updateContent { it.copy(episodes = EpisodesState.Error(tr("Sin temporadas disponibles", "No seasons available"))) }
                }
            } else {
                // Películas: se precargan las fuentes para que "Reproducir" sea inmediato.
                loadSources(episode = null)
            }
        }
    }

    /**
     * "Seguir viendo" de una serie, como Netflix: el último episodio tocado; si ya se terminó,
     * el siguiente (pasando a la temporada siguiente si era el último de la temporada).
     */
    private fun continueTarget(details: CatalogDetails): Pair<Int, Int>? {
        val prefix = WatchProgress.showPrefix(details.item.id)
        val last = watchProgress.progress.value
            .filterKeys { it.startsWith(prefix) }
            .maxByOrNull { it.value.updatedAt } ?: return null
        val parts = last.key.removePrefix(prefix).split(':')
        val season = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val episode = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (!last.value.isWatched) return season to episode

        val episodesInSeason = details.seasons.firstOrNull { it.number == season }?.episodeCount ?: Int.MAX_VALUE
        if (episode < episodesInSeason) return season to episode + 1
        val nextSeason = details.seasons.firstOrNull { it.number > season } ?: return season to episode
        return nextSeason.number to 1
    }

    fun selectSeason(season: Int, preferredEpisode: Int? = null) {
        val content = content() ?: return
        if (preferredEpisode == null && content.selectedSeason == season && content.episodes is EpisodesState.Ready) return
        updateContent { it.copy(selectedSeason = season, episodes = EpisodesState.Loading) }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            runCatching { catalogRepository.getEpisodes(content.details.item, season) }
                .onSuccess { eps ->
                    updateContent { c ->
                        c.copy(
                            episodes = EpisodesState.Ready(eps),
                            selectedEpisode = preferredEpisode?.let { n -> eps.firstOrNull { it.number == n } }
                                ?: c.selectedEpisode
                                ?: eps.firstOrNull(),
                        )
                    }
                }
                .onFailure { e -> updateContent { it.copy(episodes = EpisodesState.Error(e.message ?: "Error")) } }
        }
    }

    /**
     * Botón principal: la mejor fuente (con caída automática a las siguientes en el reproductor).
     * Si hay progreso guardado, el reproductor reanuda desde ahí salvo [fromStart].
     */
    fun play(fromStart: Boolean = false) {
        val c = content() ?: return
        pendingFromStart = fromStart
        if (c.isSeries) {
            val ep = c.selectedEpisode ?: return
            playEpisode(ep, fromStart)
            return
        }
        when (val s = c.sources) {
            // Aún sin fuentes pero con buscadores pendientes: se reproduce en cuanto llegue la primera.
            is SourcesState.Ready -> if (s.sources.isEmpty() && s.pending.isNotEmpty()) {
                pendingFromStart = fromStart
                updateContent { it.copy(pendingPlay = true) }
            } else {
                startPlayback(s.sources, startIndex = 0, fromStart = fromStart)
            }
            SourcesState.Loading -> updateContent { it.copy(pendingPlay = true) }
            SourcesState.Idle, is SourcesState.Error -> {
                updateContent { it.copy(pendingPlay = true) }
                loadSources(episode = null)
            }
        }
    }

    fun playEpisode(episode: Episode, fromStart: Boolean = false) {
        val c = content() ?: return
        pendingFromStart = fromStart
        val sameEpisode = c.selectedEpisode == episode
        val ready = c.sources as? SourcesState.Ready
        if (sameEpisode && ready != null) {
            startPlayback(ready.sources, startIndex = 0, fromStart = fromStart)
            return
        }
        updateContent { it.copy(selectedEpisode = episode, pendingPlay = true) }
        if (!(sameEpisode && c.sources == SourcesState.Loading)) loadSources(episode)
    }

    /** Panel "Fuentes": asegura que hay fuentes cargadas para lo seleccionado. */
    fun ensureSources() {
        val c = content() ?: return
        if (c.sources is SourcesState.Ready || c.sources == SourcesState.Loading) return
        if (c.isSeries && c.selectedEpisode == null) return
        loadSources(c.selectedEpisode)
    }

    /** Elegida a mano en el panel: se empieza por ella y, si no está cacheada, se siguen probando las demás. */
    fun playSource(source: StreamSource) {
        val ready = content()?.sources as? SourcesState.Ready ?: return
        startPlayback(ready.sources, startIndex = ready.sources.indexOf(source).coerceAtLeast(0), fromStart = false)
    }

    fun consumeMessage() = updateContent { it.copy(message = null) }

    /** La ficha deja de verse: un "Reanudar" que aún esperaba fuentes ya no debe arrancar solo. */
    fun onScreenHidden() {
        if (content()?.pendingPlay == true) updateContent { it.copy(pendingPlay = false) }
    }

    private fun loadSources(episode: Episode?) {
        val c = content() ?: return
        sourcesJob?.cancel()
        updateContent { it.copy(sources = SourcesState.Loading) }
        sourcesJob = viewModelScope.launch {
            // Real-Debrid retiró instantAvailability (nov-2024): "cacheado" = ya en tu librería. Se pide una vez.
            var rdCached: Set<String>? = null
            catalogRepository.getStreams(c.details.item, episode)
                .map { result ->
                    if (rdCached == null && result.sources.any { it.resolveUrl == null }) {
                        rdCached = runCatching { realDebridRepository.cachedHashes() }.getOrDefault(emptySet())
                    }
                    // Los de otros debrids / addons ya traen su marca de caché ("[TB+]", ⚡).
                    // Un mismo torrent puede salir en varios buscadores: si uno sabe que está en caché (AIOStreams, Torrentio
                    // con "[RD+]"…), se marca también en los demás (The Pirate Bay, Knaben… no lo pueden saber solos).
                    val knownCached = rdCached.orEmpty() + result.sources.filter { it.isCached }.map { it.infoHash.lowercase() }
                    val enriched = result.sources.map {
                        if (it.resolveUrl == null) it.copy(isCached = it.infoHash.lowercase() in knownCached) else it
                    }
                    result to prioritizeSourcesUseCase(enriched, displayCapabilities.supportedHdrTypes())
                }
                .catch { e ->
                    updateContent {
                        it.copy(
                            sources = SourcesState.Error(tr("No se pudieron cargar las fuentes: ${e.message ?: e.javaClass.simpleName}", "Couldn't load sources: ${e.message ?: e.javaClass.simpleName}")),
                            pendingPlay = false,
                        )
                    }
                }
                .collect { (result, sorted) ->
                    updateContent { it.copy(sources = SourcesState.Ready(sorted, result.unavailable, result.pending)) }
                    val now = content() ?: return@collect
                    // Reproducir pulsado mientras se buscaba: se espera a tener cola de verdad (3 en caché) o a que acaben
                    // todos los buscadores. Antes arrancaba con lo primero que llegaba (p. ej. 1 fuente de Comet) y si
                    // esa fallaba no había ninguna otra que probar.
                    val enoughCached = sorted.count { it.isCached } >= MIN_CACHED_TO_START
                    if (now.pendingPlay && (enoughCached || result.pending.isEmpty())) {
                        updateContent { it.copy(pendingPlay = false) }
                        if (sorted.isEmpty()) {
                            updateContent { it.copy(message = tr("No hay fuentes disponibles para esto ahora mismo", "No sources available for this right now")) }
                        } else {
                            startPlayback(sorted, startIndex = 0, fromStart = pendingFromStart)
                        }
                    }
                }
        }
    }

    private fun startPlayback(sorted: List<StreamSource>, startIndex: Int, fromStart: Boolean) {
        val c = content() ?: return
        // Otros debrids (vía Torrentio): probar una fuente que no está en su caché la pone a DESCARGAR en tu cuenta.
        // En la cola automática solo entran las que ya están en caché, más la elegida (la primera).
        // Con Real-Debrid no pasa: lo que no estaba en caché se borra solo (ver ResolveStreamUseCase).
        val queue = sorted.drop(startIndex)
            .distinctBy { it.infoHash.lowercase() } // la misma release puede salir en varios buscadores
            .filterIndexed { index, s -> index == 0 || s.resolveUrl == null || s.isCached }
            .take(MAX_QUEUE)
        if (queue.isEmpty()) return
        val item = c.details.item
        val episode = c.selectedEpisode?.takeIf { c.isSeries }
        val label = episode?.let { "${item.title} · ${it.code} · ${it.title}" } ?: item.title
        val key = if (episode != null) {
            WatchProgress.episodeKey(item.id, episode.season, episode.number)
        } else {
            WatchProgress.movieKey(item.id)
        }
        val media = PlaybackMedia(
            type = item.type,
            imdbId = item.imdbId,
            title = item.title,
            originalTitle = c.details.originalTitle,
            year = item.year,
            season = episode?.season,
            episode = episode?.number,
        )
        holder.playQueue(queue, label, progressKey = key, fromStart = fromStart, media = media)
        _events.tryEmit(DetailsEvent.OpenPlayer)
    }

    private fun content(): DetailsUiState.Content? = _uiState.value as? DetailsUiState.Content

    private fun updateContent(transform: (DetailsUiState.Content) -> DetailsUiState.Content) {
        _uiState.update { s -> if (s is DetailsUiState.Content) transform(s) else s }
    }

    private companion object {
        /**
         * Fuentes que se le pasan al reproductor. Más que intentos reales (8, en PlayerViewModel): las que RD
         * bloquea (451) se descartan al instante sin contar, y con 8 a veces se agotaban solo con esas.
         */
        const val MAX_QUEUE = 30
        /** Con "Reproducir" pendiente, fuentes en caché suficientes para arrancar sin esperar al resto. */
        const val MIN_CACHED_TO_START = 3
    }
}
