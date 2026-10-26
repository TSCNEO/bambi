package com.shieldstream.tv.data.skip

import android.util.Log
import com.shieldstream.tv.domain.SkipSegments
import com.shieldstream.tv.domain.SkipSegmentsRepository
import com.shieldstream.tv.domain.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IntroDB (introdb.app): base de datos colaborativa de intros, resúmenes y créditos por episodio (id de IMDb +
 * temporada + episodio). Lectura pública, sin clave. Es la misma fuente que usan Nuvio o el plugin de Jellyfin.
 * Código propio de Bambi: de Nuvio (GPL) solo se ha tomado la idea de usar este servicio.
 */
@Singleton
class IntroDbRepository @Inject constructor(
    base: OkHttpClient,
    private val json: Json,
) : SkipSegmentsRepository {

    // Corto: si IntroDB tarda, el episodio empieza igual (los botones simplemente no salen).
    private val client = base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, SkipSegments>()

    override suspend fun episode(imdbId: String, season: Int, episode: Int): SkipSegments {
        val key = "$imdbId:$season:$episode"
        cache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE/segments".toHttpUrl().newBuilder()
                    .addQueryParameter("imdb_id", imdbId)
                    .addQueryParameter("season", season.toString())
                    .addQueryParameter("episode", episode.toString())
                    .build()
                client.newCall(Request.Builder().url(url).header("User-Agent", "Bambi").build()).execute().use { r ->
                    if (r.code == 404) return@use SkipSegments.NONE // episodio aún sin datos
                    check(r.isSuccessful) { "IntroDB HTTP ${r.code}" }
                    parse(r.body?.string().orEmpty())
                }
            }.onFailure { Log.w(TAG, "Sin segmentos de $key: ${it.message}") }.getOrNull()
        }
        // Solo se recuerda lo que respondió: un fallo de red se vuelve a intentar en el siguiente episodio.
        return result?.also { cache[key] = it } ?: SkipSegments.NONE
    }

    private fun parse(body: String): SkipSegments {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return SkipSegments.NONE
        return SkipSegments(
            intro = root.range("intro"),
            recap = root.range("recap"),
            outro = root.range("outro"),
            postCredits = root.range("post_credits"),
        ).also { Log.i(TAG, "Segmentos ${root["imdb_id"]?.jsonPrimitive?.contentOrNull}: $it") }
    }

    /** {"start_ms":…, "end_ms":…} o en segundos. end null (créditos hasta el final) = Long.MAX_VALUE. */
    private fun JsonObject.range(name: String): TimeRange? {
        val o = this[name] as? JsonObject ?: return null
        val start = o.ms("start_ms", "start_sec") ?: return null
        val end = o.ms("end_ms", "end_sec") ?: Long.MAX_VALUE
        return TimeRange(start, end).takeIf { end > start }
    }

    private fun JsonObject.ms(msKey: String, secKey: String): Long? =
        this[msKey]?.jsonPrimitive?.longOrNull
            ?: this[secKey]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }

    private companion object {
        const val TAG = "IntroDB"
        const val BASE = "https://api.introdb.app"
    }
}
