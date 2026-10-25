package com.shieldstream.tv.domain

import com.shieldstream.tv.model.AppLanguage
import kotlinx.coroutines.flow.Flow

/**
 * Persistencia segura de la configuración de la app (token RD, proveedor de catálogo).
 */
interface SettingsRepository {
    val token: Flow<String?>
    suspend fun setToken(token: String?)

    val catalogProvider: Flow<CatalogProvider>
    suspend fun setCatalogProvider(provider: CatalogProvider)

    /** Máximo buffer del reproductor en segundos (50..100). */
    val maxBufferSeconds: Flow<Int>
    suspend fun setMaxBufferSeconds(seconds: Int)

    /** ◀▶ en el reproductor: true = búsqueda con miniaturas; false = saltos directos de 10 s (como antes). */
    val seekThumbnails: Flow<Boolean>
    suspend fun setSeekThumbnails(enabled: Boolean)

    /** Idioma de la app. Es fijo mientras la app está abierta: tras [setLanguage] hay que reiniciarla. */
    val language: AppLanguage
    suspend fun setLanguage(language: AppLanguage)

    /** Buscadores de fuentes activados (por defecto, todos). */
    val sourceProviders: Flow<Set<SourceProvider>>
    suspend fun setSourceProviders(providers: Set<SourceProvider>)

    /** Servicio debrid en uso (Real-Debrid por defecto). */
    val debridService: Flow<DebridService>
    suspend fun setDebridService(service: DebridService)
}
