package com.shieldstream.tv.network.api

import com.shieldstream.tv.network.dto.TorrentioCatalogResponse
import com.shieldstream.tv.network.dto.TorrentioStreamResponse
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Torrentio (protocolo de addons de Stremio). Devuelve streams con infoHash/fileIdx.
 * El token de Real-Debrid NO se reenvía aquí: la resolución debrid es local.
 */
interface TorrentioApi {

    /**
     * Protocolo Stremio: /stream/{type}/{id}.json
     *  - Película: type = "movie",  id = "tt1234567"
     *  - Episodio: type = "series", id = "tt1234567:{temporada}:{episodio}"
     */
    @GET("stream/{type}/{id}.json")
    suspend fun streams(
        @Path("type") type: String,
        @Path("id") id: String,
    ): TorrentioStreamResponse

    /**
     * Igual que [streams], con configuración de Torrentio en la ruta (p. ej. "torbox=CLAVE"): Torrentio consulta la
     * caché de ese servicio debrid y devuelve enlaces /resolve/. Solo para debrids distintos de Real-Debrid.
     * [config] ya va codificado (encoded = true para no escapar "=" ni "|").
     */
    /** JSON tal cual (buscadores propios: apibay, YTS). */
    @GET
    suspend fun raw(@retrofit2.http.Url url: String): okhttp3.ResponseBody

    /** Página HTML con User-Agent de navegador (scrapers de CocoScrapers). */
    @GET
    suspend fun page(@retrofit2.http.Url url: String, @retrofit2.http.Header("User-Agent") userAgent: String): okhttp3.ResponseBody

    /** POST con JSON (Knaben). */
    @retrofit2.http.POST
    suspend fun postJson(@retrofit2.http.Url url: String, @retrofit2.http.Body body: okhttp3.RequestBody): okhttp3.ResponseBody

    /** Cualquier addon de Stremio con URL completa (AIOStreams). */
    @GET
    suspend fun streamsAt(@retrofit2.http.Url url: String): TorrentioStreamResponse

    @GET("{config}/stream/{type}/{id}.json")
    suspend fun streamsWithConfig(
        @Path("config", encoded = true) config: String,
        @Path("type") type: String,
        @Path("id") id: String,
    ): TorrentioStreamResponse

    /** Torrentio no publica catálogos: se mantiene solo por compatibilidad con Ajustes. */
    @GET("catalog/{type}/top.json")
    suspend fun catalogTop(
        @Path("type") type: String,
    ): TorrentioCatalogResponse
}
