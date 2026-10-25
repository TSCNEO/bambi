package com.shieldstream.tv.data.repository

import com.shieldstream.tv.domain.TraktCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Respuesta de Trakt: código HTTP y cuerpo ya leído como JSON (null si vacío o no es JSON). */
data class TraktResponse(val code: Int, val body: JsonElement?) {
    val ok: Boolean get() = code in 200..299
}

/**
 * Cliente HTTP mínimo de la API de Trakt (v2). Sin Retrofit: son pocas llamadas y las respuestas se leen como
 * JSON genérico, así un campo nuevo o que falta no rompe nada.
 */
@Singleton
class TraktClient @Inject constructor(
    base: OkHttpClient,
    private val json: Json,
    private val credentials: TraktCredentials,
) {
    private val client = base.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", credentials.clientId)
                    // Trakt rechaza peticiones sin User-Agent propio.
                    .header("User-Agent", "Bambi/1.0 (Android TV)")
                    .build(),
            )
        }
        .build()

    suspend fun get(path: String, token: String?): TraktResponse = call(path, "GET", null, token)

    suspend fun post(path: String, body: JsonElement, token: String?): TraktResponse =
        call(path, "POST", json.encodeToString(JsonElement.serializer(), body), token)

    suspend fun delete(path: String, token: String?): TraktResponse = call(path, "DELETE", null, token)

    private suspend fun call(path: String, method: String, body: String?, token: String?): TraktResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(BASE + path)
                .method(method, body?.toRequestBody(JSON_TYPE))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
                TraktResponse(response.code, parsed)
            }
        }

    companion object {
        private const val BASE = "https://api.trakt.tv"
        private val JSON_TYPE = "application/json".toMediaType()

        /** Fechas de Trakt: "2026-10-25T18:01:32.000Z" (UTC). */
        fun parseDate(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
                val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                runCatching { return format.parse(raw)?.time }
            }
            return null
        }

        fun formatDate(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(ms)
    }
}

/** Tokens de Trakt de un perfil. Se guardan cifrados (TokenStore) bajo [storeKey]. */
@Serializable
data class TraktSession(
    val accessToken: String,
    val refreshToken: String,
    /** Cuándo caduca el access token (ms). */
    val expiresAt: Long,
    val username: String,
) {
    companion object {
        fun storeKey(profileId: String) = "trakt_$profileId"
    }
}

class TraktHttpException(code: Int) : IOException("Trakt HTTP $code")
