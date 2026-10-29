package com.shieldstream.tv.domain

import com.shieldstream.tv.model.StreamSource

/**
 * AllDebrid con su propia API (no a través de Torrentio): sirve para los buscadores que solo dan infoHash
 * (Peerflix, The Pirate Bay, Knaben, Nyaa), que Torrentio no sabe resolver con este servicio.
 */
interface AllDebridRepository {
    /** Añade el magnet a la cuenta, espera a que esté en caché y devuelve el enlace directo del archivo. */
    suspend fun resolveMagnet(source: StreamSource): String
}
