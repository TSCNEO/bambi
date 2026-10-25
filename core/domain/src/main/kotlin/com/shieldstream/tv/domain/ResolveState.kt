package com.shieldstream.tv.domain

/** Progreso de la resolución de una fuente a URL reproducible. */
sealed interface ResolveState {
    data class Progress(val message: String) : ResolveState
    data class Ready(val url: String) : ResolveState

    /**
     * [notCached] = la fuente no estaba en la caché de RD; conviene probar otra.
     * [blocked] = RD la rechazó al instante (bloqueada): no cuenta como intento.
     */
    data class Failed(val message: String, val notCached: Boolean = false, val blocked: Boolean = false) : ResolveState
}
