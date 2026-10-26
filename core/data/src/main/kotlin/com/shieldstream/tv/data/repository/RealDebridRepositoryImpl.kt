package com.shieldstream.tv.data.repository

import com.shieldstream.tv.model.tr
import android.os.SystemClock
import com.shieldstream.tv.domain.RealDebridRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.RdTorrentFile
import com.shieldstream.tv.model.RdTorrentInfo
import com.shieldstream.tv.network.api.RealDebridApi
import com.shieldstream.tv.network.dto.RdTorrentDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridRepositoryImpl @Inject constructor(
    private val api: RealDebridApi,
    private val settings: SettingsRepository,
) : RealDebridRepository {

    // Caché en memoria de la librería: Details y Resolve la consultan con segundos de diferencia,
    // así es una sola llamada. Se invalida al añadir o borrar torrents.
    private val libraryMutex = Mutex()

    /**
     * Hashes que RD ya rechazó (451) en esta sesión: no se vuelven a pedir. Pedir muchos seguidos acababa en 429
     * (demasiadas peticiones) y cortaba la cola antes de llegar a fuentes buenas.
     */
    private val blockedHashes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var libraryCache: List<RdTorrentDto> = emptyList()

    @Volatile
    private var libraryFetchedAt = 0L

    private suspend fun token(): String =
        settings.token.first() ?: error(tr("Token de Real-Debrid no configurado", "Real-Debrid token not set"))

    override suspend fun validateToken(token: String): Boolean = runCatching {
        api.user("Bearer $token").premium >= 1L
    }.getOrDefault(false)

    override suspend fun cachedHashes(): Set<String> =
        library()
            .filter { it.status == RdTorrentInfo.STATUS_DOWNLOADED }
            .mapTo(HashSet()) { it.hash.lowercase() }

    override suspend fun findDownloaded(infoHash: String): String? =
        library().firstOrNull {
            it.hash.equals(infoHash, ignoreCase = true) && it.status == RdTorrentInfo.STATUS_DOWNLOADED
        }?.id

    override suspend fun addMagnet(magnet: String): String = addMagnet(magnet, attempt = 0)

    /**
     * Ritmo de añadir torrents: RD corta con 429 si se le piden muchos seguidos (pasa con estrenos, donde casi todos
     * están bloqueados y la cola los recorre muy rápido). Como mínimo [ADD_SPACING_MS] entre uno y otro.
     */
    private val addPacing = Mutex()
    @Volatile private var lastAddAt = 0L

    override fun isBlocked(infoHash: String): Boolean = infoHash.lowercase() in blockedHashes

    private suspend fun addMagnet(magnet: String, attempt: Int): String {
        val hash = BTIH.find(magnet)?.groupValues?.get(1)?.lowercase()
        if (hash != null && hash in blockedHashes) {
            throw com.shieldstream.tv.domain.TorrentBlockedException(
                tr("Real-Debrid ha bloqueado este torrent por derechos de autor.", "Real-Debrid has blocked this torrent for copyright reasons."),
            )
        }
        val t = token()
        addPacing.withLock {
            val wait = lastAddAt + ADD_SPACING_MS - SystemClock.elapsedRealtime()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastAddAt = SystemClock.elapsedRealtime()
        }
        val resp = try {
            api.addMagnet("Bearer $t", magnet)
        } catch (e: retrofit2.HttpException) {
            // Real-Debrid explica el motivo en el cuerpo: {"error": "...", "error_code": N}.
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            android.util.Log.w("RealDebrid", "addMagnet HTTP ${e.code()}: $body")
            val code = Regex("\"error_code\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            // 429: RD pide frenar. Se espera cada vez más (2, 4 y 8 s) y se reintenta el mismo torrent.
            if ((e.code() == 429 || code == 34) && attempt < LIMIT_RETRIES) {
                kotlinx.coroutines.delay(2_000L shl attempt)
                return addMagnet(magnet, attempt + 1)
            }
            if (code == 35) {
                hash?.let { blockedHashes += it }
                throw com.shieldstream.tv.domain.TorrentBlockedException(
                    tr("Real-Debrid ha bloqueado este torrent por derechos de autor.", "Real-Debrid has blocked this torrent for copyright reasons."),
                )
            }
            error(
                when (code) {
                    35 -> tr("Real-Debrid ha bloqueado este torrent por derechos de autor.", "Real-Debrid has blocked this torrent for copyright reasons.")
                    21 -> tr("Tienes demasiadas descargas activas en Real-Debrid.", "Too many active downloads on Real-Debrid.")
                    34 -> tr("Real-Debrid pide esperar un momento (demasiadas peticiones).", "Real-Debrid asks to wait a moment (too many requests).")
                    8, 9 -> tr("Token de Real-Debrid no válido o sin permisos.", "Invalid Real-Debrid token or missing permissions.")
                    else -> tr("Real-Debrid rechazó el torrent (HTTP ${e.code()}${code?.let { ", código $it" } ?: ""}).", "Real-Debrid rejected the torrent (HTTP ${e.code()}${code?.let { ", code $it" } ?: ""}).")
                },
            )
        }
        require(resp.id.isNotBlank()) { tr("Real-Debrid no devolvió id de torrent", "Real-Debrid returned no torrent id") }
        invalidateLibrary()
        return resp.id
    }

    override suspend fun torrentInfo(torrentId: String): RdTorrentInfo {
        val t = token()
        val dto = api.torrentInfo("Bearer $t", torrentId)
        return RdTorrentInfo(
            id = dto.id,
            hash = dto.hash,
            status = dto.status,
            files = dto.files.map { f ->
                RdTorrentFile(
                    id = f.id,
                    name = f.path,
                    sizeBytes = f.bytes,
                    selected = f.selected == 1,
                    link = null,
                )
            },
            links = dto.links,
        )
    }

    override suspend fun selectFiles(torrentId: String, fileIds: List<Int>) {
        val t = token()
        val resp = api.selectFiles("Bearer $t", torrentId, fileIds.joinToString(","))
        check(resp.isSuccessful) { tr("Real-Debrid rechazó la selección de archivos (HTTP ${resp.code()})", "Real-Debrid rejected the file selection (HTTP ${resp.code()})") }
    }

    override suspend fun deleteTorrent(torrentId: String) {
        runCatching { api.deleteTorrent("Bearer ${token()}", torrentId) }
        invalidateLibrary()
    }

    override suspend fun unrestrict(link: String): String {
        val t = token()
        val resp = api.unrestrict("Bearer $t", link)
        require(resp.download.isNotBlank()) { tr("No se pudo desbloquear el enlace", "Couldn't unrestrict the link") }
        return resp.download
    }

    private suspend fun library(): List<RdTorrentDto> {
        return libraryMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (libraryFetchedAt != 0L && now - libraryFetchedAt < LIBRARY_TTL_MS) {
                libraryCache
            } else {
                val resp = api.torrents("Bearer ${token()}", limit = LIBRARY_LIMIT)
                // 204 = librería vacía (sin body). Si falla (401, 503…) no se cachea el error.
                if (resp.isSuccessful) {
                    libraryCache = resp.body().orEmpty()
                    libraryFetchedAt = now
                    libraryCache
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun invalidateLibrary() {
        libraryFetchedAt = 0L
    }

    private companion object {
        const val LIBRARY_TTL_MS = 60_000L

        /** Los más recientes primero; 1000 cubre de sobra el uso normal. */
        const val LIBRARY_LIMIT = 1000

        val BTIH = Regex("btih:([a-fA-F0-9]{40})")
        const val ADD_SPACING_MS = 1_000L
        const val LIMIT_RETRIES = 3
    }
}
