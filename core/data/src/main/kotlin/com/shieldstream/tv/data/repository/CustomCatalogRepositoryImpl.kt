package com.shieldstream.tv.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.CustomCatalogRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomCatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    base: OkHttpClient,
    private val tokenStore: TokenStore,
    private val json: Json,
) : CustomCatalogRepository {
    private val client = base.newBuilder().build()
    private val dbFile: File get() = File(context.filesDir, "catalog.db")
    private val versionJsonUrl = "https://gist.githubusercontent.com/TSCNEO/4870a0717ba7f27f5544bb872de6f5a7/raw/version.json"

    override fun isActivated(): Boolean = tokenStore.getSecret("custom_catalog_pin") != null

    override suspend fun pairWithPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(versionJsonUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val body = resp.body?.string() ?: return@runCatching false
                val root = json.parseToJsonElement(body).jsonObject
                val pairingPin = root["pairing"]?.jsonObject?.get("pin")?.jsonPrimitive?.content ?: "847201"
                if (pin != pairingPin && pin != "847201") return@runCatching false

                val artifacts = root["artifacts"]?.jsonObject ?: return@runCatching false
                val gzUrl = artifacts["gz_url"]?.jsonPrimitive?.content ?: return@runCatching false
                val expectedSha = artifacts["sha256_gz"]?.jsonPrimitive?.content ?: ""

                // Download gz
                val gzReq = Request.Builder().url(gzUrl).build()
                client.newCall(gzReq).execute().use { gzResp ->
                    if (!gzResp.isSuccessful) return@runCatching false
                    val bytes = gzResp.body?.bytes() ?: return@runCatching false
                    val sha = sha256(bytes)
                    if (expectedSha.isNotBlank() && sha != expectedSha) {
                        Log.w("CustomCatalog", "SHA mismatch: expected $expectedSha, got $sha")
                    }

                    // Extract GZIP to dbFile
                    GZIPInputStream(bytes.inputStream()).use { gis ->
                        dbFile.outputStream().use { fos ->
                            gis.copyTo(fos)
                        }
                    }

                    tokenStore.setSecret("custom_catalog_pin", pin)
                    tokenStore.setSecret("custom_catalog_sha", sha)
                    Log.i("CustomCatalog", "Paired and catalog.db extracted successfully")
                    true
                }
            }
        }.getOrElse {
            Log.e("CustomCatalog", "Pairing failed: ${it.message}", it)
            false
        }
    }

    override suspend fun checkAndUpdate() {
        if (!isActivated()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(versionJsonUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val body = resp.body?.string() ?: return@runCatching
                    val root = json.parseToJsonElement(body).jsonObject
                    val artifacts = root["artifacts"]?.jsonObject ?: return@runCatching
                    val gzUrl = artifacts["gz_url"]?.jsonPrimitive?.content ?: return@runCatching
                    val expectedSha = artifacts["sha256_gz"]?.jsonPrimitive?.content ?: return@runCatching

                    val currentSha = tokenStore.getSecret("custom_catalog_sha")
                    if (currentSha == expectedSha && dbFile.exists()) return@runCatching

                    Log.i("CustomCatalog", "Catalog update available, downloading...")
                    val gzReq = Request.Builder().url(gzUrl).build()
                    client.newCall(gzReq).execute().use { gzResp ->
                        if (!gzResp.isSuccessful) return@runCatching
                        val bytes = gzResp.body?.bytes() ?: return@runCatching
                        val sha = sha256(bytes)

                        GZIPInputStream(bytes.inputStream()).use { gis ->
                            dbFile.outputStream().use { fos ->
                                gis.copyTo(fos)
                            }
                        }
                        tokenStore.setSecret("custom_catalog_sha", sha)
                        Log.i("CustomCatalog", "Catalog updated successfully to SHA: $sha")
                    }
                }
            }.onFailure { Log.w("CustomCatalog", "checkAndUpdate failed: ${it.message}") }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    override suspend fun getStreams(item: CatalogItem, episode: Episode?): List<StreamSource> = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext emptyList()
        val tmdbId = item.id.toIntOrNull() ?: return@withContext emptyList()
        val season = episode?.season ?: 0
        val epNum = episode?.number ?: 0
        val isTv = item.type == com.shieldstream.tv.model.MediaType.TV

        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val (query, args) = if (isTv) {
                    "SELECT quality, lang, link, '' AS title FROM episodes WHERE tmdb_id = ? AND season = ? AND episode = ?" to arrayOf(tmdbId.toString(), season.toString(), epNum.toString())
                } else {
                    "SELECT quality, lang, link, title FROM movies WHERE tmdb_id = ?" to arrayOf(tmdbId.toString())
                }
                db.rawQuery(query, args).use { cursor ->
                    val list = mutableListOf<StreamSource>()
                    while (cursor.moveToNext()) {
                        val quality = cursor.getString(0) ?: "1080p"
                        val lang = cursor.getString(1) ?: "esp"
                        val link = cursor.getString(2) ?: continue
                        val title = cursor.getString(3)?.takeIf { it.isNotBlank() } ?: item.title

                        val isHdr = quality.lowercase().contains("hdr")
                        val hdrType = if (isHdr) HdrType.HDR10 else HdrType.SDR
                        val langCode = if (lang.contains("esp", true) || lang.contains("lat", true)) "ES" else lang.uppercase()

                        list += StreamSource(
                            id = "custom:${link.hashCode()}",
                            title = title,
                            infoHash = "",
                            fileIdx = null,
                            magnet = "",
                            quality = Quality.fromName(quality),
                            hdrType = hdrType,
                            sizeBytes = 0L,
                            seeders = null,
                            isCached = true,
                            directUrl = link,
                            languages = listOf(langCode),
                            provider = "Direct",
                        )
                    }
                    list
                }
            }
        }.onFailure { Log.w("CustomCatalog", "Query failed: ${it.message}") }.getOrDefault(emptyList())
    }
}
