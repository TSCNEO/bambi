package com.shieldstream.tv.domain

import com.shieldstream.tv.model.RdTorrentInfo

/**
 * Contrato de acceso a la API privada de Real-Debrid.
 *
 * RD retiró /torrents/instantAvailability en nov-2024: ya no se puede preguntar
 * "¿está cacheado?" sin añadir el magnet. La única señal barata es tu propia librería:
 * un torrent en estado `downloaded` está cacheado seguro.
 */
interface RealDebridRepository {
    /** Devuelve true si el token es válido y la cuenta es premium. */
    suspend fun validateToken(token: String): Boolean

    /** infoHashes (minúsculas) ya descargados en tu librería. Una sola llamada, cacheada ~60 s. */
    suspend fun cachedHashes(): Set<String>

    /** Id de un torrent ya descargado en tu librería con ese infoHash, o null. */
    suspend fun findDownloaded(infoHash: String): String?

    /** Añade un magnet a la nube y devuelve el id. Cada llamada crea una entrada nueva en la librería. */
    suspend fun addMagnet(magnet: String): String

    /** Estado, archivos y enlaces de un torrent. */
    suspend fun torrentInfo(torrentId: String): RdTorrentInfo

    /** Selecciona los archivos (ids de RD, base 1) a descargar en la nube. */
    suspend fun selectFiles(torrentId: String, fileIds: List<Int>)

    /** Real-Debrid ya rechazó este torrent (451, derechos de autor) en esta sesión: en Fuentes se marca. */
    fun isBlocked(infoHash: String): Boolean = false

    /** Borra un torrent de la librería. Nunca lanza: es limpieza. */
    suspend fun deleteTorrent(torrentId: String)

    /** Desbloquea un enlace devolviendo la URL de descarga directa. */
    suspend fun unrestrict(link: String): String
}
