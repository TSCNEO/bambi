package com.shieldstream.tv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Qué se ve en "Mi lista": todo, solo series o solo películas. */
enum class MyListFilter { ALL, TV, MOVIES }

sealed interface MyListUiState {
    data object Loading : MyListUiState
    /** Lista vacía (o nada de este tipo): se explica cómo añadir. */
    data class Empty(val filter: MyListFilter) : MyListUiState
    data class Content(val filter: MyListFilter, val items: List<CatalogItem>) : MyListUiState
}

/**
 * Pestaña "Mi lista" del menú: TODO lo guardado por el perfil (la fila del Inicio solo enseña los 40 últimos).
 * Lo último añadido, primero. Se actualiza sola al añadir/quitar desde una ficha o al llegar cambios de Trakt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MyListViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val myList: MyListRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(MyListFilter.ALL)

    /** Fichas ya pedidas a TMDB: quitar o añadir un título no vuelve a pedir los demás. */
    private val loaded = ConcurrentHashMap<String, CatalogItem>()

    val uiState: StateFlow<MyListUiState> = combine(myList.entries, filter) { entries, f -> entries to f }
        .mapLatest { (entries, f) ->
            val wanted = entries.filter {
                when (f) {
                    MyListFilter.ALL -> true
                    MyListFilter.TV -> it.type == MediaType.TV
                    MyListFilter.MOVIES -> it.type == MediaType.MOVIE
                }
            }
            val items = coroutineScope {
                wanted.map { e ->
                    async {
                        loaded[e.key] ?: withTimeoutOrNull(5_000) {
                            runCatching { catalogRepository.getItem(e.type, e.id) }.getOrNull()
                        }?.also { loaded[e.key] = it }
                    }
                }.awaitAll().filterNotNull()
            }
            if (items.isEmpty()) MyListUiState.Empty(f) else MyListUiState.Content(f, items)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyListUiState.Loading)

    fun setFilter(value: MyListFilter) {
        filter.value = value
    }

    /** Mantener OK sobre una tarjeta: fuera de la lista (con Trakt, también de la watchlist). */
    fun remove(item: CatalogItem) = myList.remove(item.type, item.id)
}
