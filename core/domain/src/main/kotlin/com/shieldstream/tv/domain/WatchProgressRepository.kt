package com.shieldstream.tv.domain

import com.shieldstream.tv.model.WatchProgress
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Progreso de visionado persistente (sobrevive a cerrar la app y reiniciar la Shield), del perfil activo:
 * al cambiar de perfil, [progress] pasa a ser el suyo.
 * Claves: [WatchProgress.movieKey] / [WatchProgress.episodeKey].
 */
interface WatchProgressRepository {
    /** Todo el progreso guardado; la UI lo observa para pintar ticks y barras. */
    val progress: StateFlow<Map<String, WatchProgress>>

    fun get(key: String): WatchProgress?

    /** Síncrono y barato (SharedPreferences.apply): se puede llamar desde onStop/onCleared. */
    fun save(key: String, positionMs: Long, durationMs: Long)

    fun clear(key: String)

    /** Claves que el usuario ha quitado con [clear] (p. ej. "Quitar de Seguir viendo"): Trakt las borra también. */
    val cleared: SharedFlow<String>

    /** Progreso traído de Trakt: se guarda tal cual (con su fecha) y no emite [cleared]. */
    fun importRemote(entries: Map<String, WatchProgress>)

    /** Lo que Trakt ya no tiene (lo quitaste en la otra Shield): se borra aquí sin emitir [cleared]. */
    fun removeRemote(keys: Collection<String>)
}
