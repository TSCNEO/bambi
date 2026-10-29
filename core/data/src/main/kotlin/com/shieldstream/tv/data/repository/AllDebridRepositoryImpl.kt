package com.shieldstream.tv.data.repository

import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.AllDebridRepository
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.model.StreamSource
import com.shieldstream.tv.model.tr
import com.shieldstream.tv.network.api.AllDebridApi
import com.shieldstream.tv.network.dto.AllDebridLinkDto
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AllDebrid desde la Shield, igual que RealDebridRepositoryImpl: el magnet se añade a la cuenta del usuario y el
 * enlace directo se pide a la API, así no pasa por Torrentio (que no resuelve los buscadores propios).
 *
 * Si el magnet no está en la caché se borra de la cuenta (no se deja descargando) y se avisa para que el
 * reproductor pruebe otra fuente.
 */
@Singleton
class AllDebridRepositoryImpl @Inject constructor(
    private val api: AllDebridApi,
    private val store: TokenStore,
) : AllDebridRepository {

    private fun key(): String =
        store.getSecret(TokenStore.keyNameFor(ApiService.ALLDEBRID))?.trim()?.takeIf { it.isNotBlank() }
            ?: error(tr("Clave de AllDebrid no configurada", "AllDebrid key not set"))

    override suspend fun resolveMagnet(source: StreamSource): String {
        val apiKey = key()
        val uploadResp = api.uploadMagnet(apiKey = apiKey, magnet = source.magnet)
        if (uploadResp.status != "success") {
            val msg = uploadResp.error?.message ?: tr("Error añadiendo magnet a AllDebrid", "Error adding magnet to AllDebrid")
            error(msg)
        }
        val magnet = uploadResp.data?.magnets?.firstOrNull()
            ?: error(tr("AllDebrid no devolvió el magnet", "AllDebrid returned no magnet"))

        magnet.error?.let {
            error(it.message ?: tr("Error en magnet de AllDebrid", "AllDebrid magnet error"))
        }

        val magnetId = magnet.id
        var isReady = magnet.ready

        // statusCode 4 = listo en la caché. Un magnet cacheado tarda 1–3 s; el resto no llega: fuera.
        for (attempt in 1..CACHE_CHECKS) {
            if (isReady) break
            delay(POLL_INTERVAL_MS)
            val m = runCatching { api.magnetStatus(apiKey = apiKey, id = magnetId) }.getOrNull()
                ?.data?.magnets?.firstOrNull()
            if (m?.statusCode == STATUS_READY) isReady = true
        }

        if (!isReady) {
            runCatching { api.deleteMagnet(apiKey = apiKey, id = magnetId) }
            throw IllegalStateException(
                tr(
                    "Esta fuente no está en la caché de AllDebrid. Prueba otra.",
                    "This source isn't cached on AllDebrid. Try another.",
                ),
            )
        }

        val statusResp = api.magnetStatus(apiKey = apiKey, id = magnetId)
        val m = statusResp.data?.magnets?.firstOrNull()
            ?: error(tr("No se pudo obtener el estado del magnet en AllDebrid", "Couldn't get AllDebrid magnet status"))

        val targetLink = pickFile(m.links, source)
            ?: error(tr("El torrent no contiene archivos de vídeo en AllDebrid", "Torrent has no video files in AllDebrid"))

        val unlockResp = api.unlockLink(apiKey = apiKey, link = targetLink.link)
        val unlocked = unlockResp.data?.link?.takeIf { it.isNotBlank() }
        if (unlockResp.status != "success" || unlocked == null) {
            val msg = unlockResp.error?.message
                ?: tr("No se pudo desbloquear el enlace en AllDebrid", "Couldn't unlock link in AllDebrid")
            error(msg)
        }

        return unlocked
    }

    /** El archivo del episodio dentro de un pack: por nombre, por SxxEyy y, si no, el vídeo más grande. */
    private fun pickFile(links: List<AllDebridLinkDto>, source: StreamSource): AllDebridLinkDto? {
        val videos = links.filter { l -> VIDEO_EXTENSIONS.any { l.filename.endsWith(".$it", ignoreCase = true) } }
        val pool = videos.ifEmpty { links }
        if (pool.isEmpty()) return null

        source.fileName?.let { name ->
            pool.firstOrNull { it.filename.equals(name, ignoreCase = true) }?.let { return it }
        }

        val s = source.season
        val e = source.episode
        if (s != null && e != null) {
            val tag = "s%02de%02d".format(s, e)
            pool.firstOrNull { it.filename.lowercase().contains(tag) }?.let { return it }
        }

        return pool.maxByOrNull { it.size }
    }

    private companion object {
        val VIDEO_EXTENSIONS = listOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "flv", "webm", "ts")

        /** 4 intentos × 1,5 s: un magnet en caché de AllDebrid está listo en 2–4 s. */
        const val CACHE_CHECKS = 4
        const val POLL_INTERVAL_MS = 1_500L

        /** AllDebrid: 4 = listo (el magnet está completo en la cuenta). */
        const val STATUS_READY = 4
    }
}
