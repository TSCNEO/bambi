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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    private val versionJsonUrl = "https://gist.githubusercontent.com/raw/4870a0717ba7f27f5544bb872de6f5a7/version.json"

    companion object {
        private const val VAULT_SALT = "bambi_vault_salt_#984x"
    }

    override fun isActivated(): Boolean = tokenStore.getSecret("custom_catalog_pin") != null

    override fun getCatalogVersion(): String? {
        val stored = tokenStore.getSecret("custom_catalog_version")
        if (!stored.isNullOrBlank()) return stored
        if (!dbFile.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key = 'moria_version' OR key = 'version' LIMIT 1", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        }.getOrNull()
    }

    override suspend fun pairWithPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(versionJsonUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val body = resp.body?.string() ?: return@runCatching false
                val root = json.parseToJsonElement(body).jsonObject
                val versionStr = root["version"]?.jsonPrimitive?.content
                    ?: root["moria_version"]?.jsonPrimitive?.content ?: "554"
                val artifacts = root["artifacts"]?.jsonObject ?: return@runCatching false

                val binUrl = artifacts["bin_url"]?.jsonPrimitive?.content
                    ?: artifacts["url"]?.jsonPrimitive?.content
                    ?: artifacts["gz_url"]?.jsonPrimitive?.content
                    ?: return@runCatching false
                val expectedSha = artifacts["sha256_bin"]?.jsonPrimitive?.content
                    ?: artifacts["sha256"]?.jsonPrimitive?.content
                    ?: artifacts["sha256_gz"]?.jsonPrimitive?.content
                    ?: ""

                // Download encrypted bin
                val binReq = Request.Builder().url(binUrl).build()
                client.newCall(binReq).execute().use { binResp ->
                    if (!binResp.isSuccessful) return@runCatching false
                    val bytes = binResp.body?.bytes() ?: return@runCatching false
                    val sha = sha256(bytes)
                    if (expectedSha.isNotBlank() && sha != expectedSha) {
                        Log.w("CustomCatalog", "SHA mismatch: expected $expectedSha, got $sha")
                    }

                    // Decrypt AES-256 payload using PIN + SALT
                    val decryptedGz = decryptCatalog(bytes, pin)

                    // Extract GZIP to dbFile
                    GZIPInputStream(decryptedGz.inputStream()).use { gis ->
                        dbFile.outputStream().use { fos ->
                            gis.copyTo(fos)
                        }
                    }

                    tokenStore.setSecret("custom_catalog_pin", pin)
                    tokenStore.setSecret("custom_catalog_sha", sha)
                    tokenStore.setSecret("custom_catalog_version", versionStr)
                    Log.i("CustomCatalog", "Paired, decrypted and catalog.db extracted successfully (v$versionStr)")
                    true
                }
            }
        }.getOrElse {
            Log.e("CustomCatalog", "Pairing failed: ${it.message}", it)
            false
        }
    }

    override suspend fun forceUpdate(): CustomCatalogRepository.SyncResult = withContext(Dispatchers.IO) {
        if (!isActivated()) return@withContext CustomCatalogRepository.SyncResult.Error("No activado")
        val pin = tokenStore.getSecret("custom_catalog_pin")
            ?: return@withContext CustomCatalogRepository.SyncResult.Error("Sin PIN")

        runCatching {
            val req = Request.Builder().url(versionJsonUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching CustomCatalogRepository.SyncResult.Error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: return@runCatching CustomCatalogRepository.SyncResult.Error("Sin respuesta")
                val root = json.parseToJsonElement(body).jsonObject
                val versionStr = root["version"]?.jsonPrimitive?.content
                    ?: root["moria_version"]?.jsonPrimitive?.content ?: "?"
                val artifacts = root["artifacts"]?.jsonObject
                    ?: return@runCatching CustomCatalogRepository.SyncResult.Error("Artefactos no encontrados")

                val binUrl = artifacts["bin_url"]?.jsonPrimitive?.content
                    ?: artifacts["url"]?.jsonPrimitive?.content
                    ?: artifacts["gz_url"]?.jsonPrimitive?.content
                    ?: return@runCatching CustomCatalogRepository.SyncResult.Error("URL no encontrada")
                val expectedSha = artifacts["sha256_bin"]?.jsonPrimitive?.content
                    ?: artifacts["sha256"]?.jsonPrimitive?.content
                    ?: artifacts["sha256_gz"]?.jsonPrimitive?.content ?: ""

                val currentSha = tokenStore.getSecret("custom_catalog_sha")
                if (currentSha == expectedSha && dbFile.exists()) {
                    return@runCatching CustomCatalogRepository.SyncResult.UpToDate(versionStr)
                }

                val binReq = Request.Builder().url(binUrl).build()
                client.newCall(binReq).execute().use { binResp ->
                    if (!binResp.isSuccessful) return@runCatching CustomCatalogRepository.SyncResult.Error("HTTP ${binResp.code} al descargar")
                    val bytes = binResp.body?.bytes()
                        ?: return@runCatching CustomCatalogRepository.SyncResult.Error("Descarga vacía")
                    val sha = sha256(bytes)

                    val decryptedGz = decryptCatalog(bytes, pin)
                    GZIPInputStream(decryptedGz.inputStream()).use { gis ->
                        dbFile.outputStream().use { fos ->
                            gis.copyTo(fos)
                        }
                    }

                    tokenStore.setSecret("custom_catalog_sha", sha)
                    tokenStore.setSecret("custom_catalog_version", versionStr)
                    Log.i("CustomCatalog", "Catalog updated successfully to v$versionStr")
                    CustomCatalogRepository.SyncResult.Updated(versionStr)
                }
            }
        }.getOrElse {
            Log.e("CustomCatalog", "forceUpdate error: ${it.message}", it)
            CustomCatalogRepository.SyncResult.Error(it.message ?: "Error desconocido")
        }
    }

    override suspend fun checkAndUpdate() {
        if (!isActivated()) return
        withContext(Dispatchers.IO) {
            runCatching {
                forceUpdate()
            }.onFailure { Log.w("CustomCatalog", "checkAndUpdate failed: ${it.message}") }
        }
    }

    private fun decryptCatalog(encryptedBytes: ByteArray, pin: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest((VAULT_SALT + pin).toByteArray(Charsets.UTF_8))
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val iv = encryptedBytes.copyOfRange(0, 16)
        val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(cipherText)
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
