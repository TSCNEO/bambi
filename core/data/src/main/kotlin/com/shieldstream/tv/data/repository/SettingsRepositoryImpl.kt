package com.shieldstream.tv.data.repository

import com.shieldstream.tv.domain.SourceProvider
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.AppLanguage
import android.content.Context
import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.CatalogProvider
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenStore: TokenStore,
) : SettingsRepository {

    private val prefs = context.getSharedPreferences(AppLanguage.PREFS, Context.MODE_PRIVATE)

    private val tokenFlow = MutableStateFlow(tokenStore.token)
    private val catalogProviderFlow = MutableStateFlow(
        runCatching { CatalogProvider.valueOf(prefs.getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(CatalogProvider.COMBINED),
    )
    private val maxBufferFlow = MutableStateFlow(prefs.getInt(KEY_BUFFER, DEFAULT_BUFFER))
    private val seekThumbnailsFlow = MutableStateFlow(prefs.getBoolean(KEY_SEEK_THUMBNAILS, true))
    private val debridFlow = MutableStateFlow(
        runCatching { DebridService.valueOf(prefs.getString(KEY_DEBRID, null) ?: "") }
            .getOrDefault(DebridService.REAL_DEBRID),
    )
    override val debridService: Flow<DebridService> = debridFlow

    // Se guardan los DESACTIVADOS: así un buscador nuevo (actualización) sale activado sin tocar nada.
    // (Antes se guardaban los activados y los nuevos quedaban apagados.)
    private val providersFlow = MutableStateFlow(
        SourceProvider.entries.toSet() - prefs.getStringSet(KEY_PROVIDERS_OFF, emptySet()).orEmpty()
            .mapNotNull { n -> SourceProvider.entries.firstOrNull { it.name == n } }.toSet(),
    )
    override val sourceProviders: Flow<Set<SourceProvider>> = providersFlow

    override suspend fun setSourceProviders(providers: Set<SourceProvider>) {
        prefs.edit().putStringSet(KEY_PROVIDERS_OFF, (SourceProvider.entries.toSet() - providers).map { it.name }.toSet()).apply()
        providersFlow.value = providers
    }

    override suspend fun setDebridService(service: DebridService) {
        prefs.edit().putString(KEY_DEBRID, service.name).apply()
        debridFlow.value = service
    }

    override val language: AppLanguage get() = Lang.current

    /** commit() (no apply): la app se reinicia justo después y el valor tiene que estar ya en disco. */
    override suspend fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(AppLanguage.PREF_KEY, language.name).commit()
    }

    override val token: Flow<String?> = tokenFlow
    override val catalogProvider: Flow<CatalogProvider> = catalogProviderFlow
    override val maxBufferSeconds: Flow<Int> = maxBufferFlow
    override val seekThumbnails: Flow<Boolean> = seekThumbnailsFlow

    override suspend fun setSeekThumbnails(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SEEK_THUMBNAILS, enabled).apply()
        seekThumbnailsFlow.value = enabled
    }

    override suspend fun setToken(token: String?) {
        tokenStore.token = token
        tokenFlow.value = token
    }

    override suspend fun setCatalogProvider(provider: CatalogProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        catalogProviderFlow.value = provider
    }

    override suspend fun setMaxBufferSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_BUFFER, seconds.coerceIn(MIN_BUFFER, MAX_BUFFER)).apply()
        maxBufferFlow.value = seconds.coerceIn(MIN_BUFFER, MAX_BUFFER)
    }

    private companion object {
        const val KEY_PROVIDER = "catalog_provider"
        const val KEY_BUFFER = "max_buffer_seconds"
        const val KEY_SEEK_THUMBNAILS = "seek_thumbnails"
        const val KEY_DEBRID = "debrid_service"
        const val KEY_PROVIDERS_OFF = "source_providers_off"
        const val DEFAULT_BUFFER = 80
        const val MIN_BUFFER = 50
        const val MAX_BUFFER = 100
    }
}
