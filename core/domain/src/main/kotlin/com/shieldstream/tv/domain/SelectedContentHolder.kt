package com.shieldstream.tv.domain

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.PlaybackMedia
import com.shieldstream.tv.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado de navegación compartido en memoria (selección actual).
 * Evita serializar objetos complejos como argumentos de navegación.
 */
class SelectedContentHolder {
    private val _selectedItem = MutableStateFlow<CatalogItem?>(null)
    val selectedItem: StateFlow<CatalogItem?> = _selectedItem.asStateFlow()

    /**
     * Cola de reproducción: el reproductor prueba las fuentes en orden hasta que una
     * esté en la caché de Real-Debrid (sin instantAvailability no hay otra forma de saberlo).
     */
    private val _queue = MutableStateFlow<List<StreamSource>>(emptyList())
    val queue: StateFlow<List<StreamSource>> = _queue.asStateFlow()

    /** Texto que muestra el reproductor mientras resuelve ("Dune: Parte dos", "Severance · T1:E3"). */
    private val _playLabel = MutableStateFlow("")
    val playLabel: StateFlow<String> = _playLabel.asStateFlow()

    /** Clave de progreso de lo que se va a reproducir (ver WatchProgress.movieKey / episodeKey). */
    private val _progressKey = MutableStateFlow<String?>(null)
    val progressKey: StateFlow<String?> = _progressKey.asStateFlow()

    /** true = "Desde el principio": ignorar el punto guardado. */
    private val _startFromBeginning = MutableStateFlow(false)
    val startFromBeginning: StateFlow<Boolean> = _startFromBeginning.asStateFlow()

    /** Qué se reproduce (título, IMDb, temporada/episodio): lo usa la búsqueda de subtítulos externos. */
    private val _media = MutableStateFlow<PlaybackMedia?>(null)
    val media: StateFlow<PlaybackMedia?> = _media.asStateFlow()

    fun selectItem(item: CatalogItem) {
        _selectedItem.value = item
        _queue.value = emptyList()
    }

    fun playQueue(
        sources: List<StreamSource>,
        label: String,
        progressKey: String? = null,
        fromStart: Boolean = false,
        media: PlaybackMedia? = null,
    ) {
        _media.value = media
        _queue.value = sources
        _playLabel.value = label
        _progressKey.value = progressKey
        _startFromBeginning.value = fromStart
    }
}
