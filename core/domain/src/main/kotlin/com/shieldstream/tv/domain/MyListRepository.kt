package com.shieldstream.tv.domain

import com.shieldstream.tv.model.MediaType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Una película o serie de "Mi lista" (ids de TMDB). */
data class MyListEntry(val type: MediaType, val id: String, val addedAt: Long) {
    val key: String get() = keyOf(type, id)

    companion object {
        fun keyOf(type: MediaType, id: String) = "${type.name}_$id"
    }
}

/** Cambio hecho a mano en esta tele (Trakt lo sube a la watchlist). */
data class MyListChange(val type: MediaType, val id: String, val added: Boolean)

/**
 * "Mi lista" del perfil activo, como en Netflix: se añade desde la ficha y sale como fila en el Inicio.
 * Con Trakt enlazado, es la watchlist de Trakt (en las dos Shields).
 */
interface MyListRepository {
    /** Lo último añadido, primero. Cambia al cambiar de perfil. */
    val entries: StateFlow<List<MyListEntry>>

    fun contains(type: MediaType, id: String): Boolean

    fun add(type: MediaType, id: String)

    fun remove(type: MediaType, id: String)

    /** Cambios locales (no los que vienen de Trakt). */
    val changes: SharedFlow<MyListChange>

    /** Lo que viene de Trakt: añade [added] y quita [removedKeys] sin emitir [changes]. */
    fun importRemote(added: List<MyListEntry>, removedKeys: Collection<String>)
}
