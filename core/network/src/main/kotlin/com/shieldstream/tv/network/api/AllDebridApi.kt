package com.shieldstream.tv.network.api

import com.shieldstream.tv.network.dto.AllDebridResponse
import com.shieldstream.tv.network.dto.AllDebridStatusData
import com.shieldstream.tv.network.dto.AllDebridUnlockData
import com.shieldstream.tv.network.dto.AllDebridUploadData
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * API de AllDebrid (https://api.alldebrid.com). La clave va en cada petición (`apikey`), porque se lee de Ajustes.
 *
 * Flujo para reproducir un magnet (como hace la integración de Torrentio, pero desde la Shield):
 *  1. /v4/magnet/upload → id del magnet en la cuenta.
 *  2. /v4/magnet/status → statusCode 4 = listo (caché); 0–3 = aún procesando.
 *  3. /v4/link/unlock → enlace directo del archivo elegido.
 */
interface AllDebridApi {
    @FormUrlEncoded
    @POST("v4/magnet/upload")
    suspend fun uploadMagnet(
        @Query("agent") agent: String = "bambi",
        @Query("apikey") apiKey: String,
        @Field("magnets[]") magnet: String,
    ): AllDebridResponse<AllDebridUploadData>

    @FormUrlEncoded
    @POST("v4/magnet/status")
    suspend fun magnetStatus(
        @Query("agent") agent: String = "bambi",
        @Query("apikey") apiKey: String,
        @Field("id") id: Long,
    ): AllDebridResponse<AllDebridStatusData>

    @GET("v4/link/unlock")
    suspend fun unlockLink(
        @Query("agent") agent: String = "bambi",
        @Query("apikey") apiKey: String,
        @Query("link") link: String,
    ): AllDebridResponse<AllDebridUnlockData>

    @FormUrlEncoded
    @POST("v4/magnet/delete")
    suspend fun deleteMagnet(
        @Query("agent") agent: String = "bambi",
        @Query("apikey") apiKey: String,
        @Field("id") id: Long,
    ): AllDebridResponse<Unit?>
}
