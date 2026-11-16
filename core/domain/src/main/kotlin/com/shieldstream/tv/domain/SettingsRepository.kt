package com.shieldstream.tv.domain

import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.DeviceOptimization
import com.shieldstream.tv.model.SourceExclusions
import com.shieldstream.tv.model.SubtitleAppearance
import com.shieldstream.tv.model.SubtitlePreference
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val token: Flow<String?>
    suspend fun setToken(token: String?)

    val catalogProvider: Flow<CatalogProvider>
    suspend fun setCatalogProvider(provider: CatalogProvider)

    val deviceOptimization: Flow<DeviceOptimization>
    suspend fun setDeviceOptimization(optimization: DeviceOptimization)

    val maxBufferSeconds: Flow<Int>
    suspend fun setMaxBufferSeconds(seconds: Int)

    val lightweightUi: Flow<Boolean>
    val lightweightUiEnabled: Boolean
    suspend fun setLightweightUi(enabled: Boolean)

    /** Blur the backdrop behind Home rows. App-wide; on by default. */
    /** Intro de Bambi cada vez que se abre la app (si no, solo al arrancar o cuando hay que recargar el Inicio). */
    val introAlways: Flow<Boolean>
    val introAlwaysEnabled: Boolean
    suspend fun setIntroAlways(enabled: Boolean)

    val softBackdrops: Flow<Boolean>
    val softBackdropsEnabled: Boolean
    suspend fun setSoftBackdrops(enabled: Boolean)

    /** Load full-screen backdrops at TMDB's original size. App-wide; off by default. */
    val highResBackdrops: Flow<Boolean>
    val highResBackdropsEnabled: Boolean
    suspend fun setHighResBackdrops(enabled: Boolean)

    val seekThumbnails: Flow<Boolean>
    suspend fun setSeekThumbnails(enabled: Boolean)

    val language: AppLanguage
    suspend fun setLanguage(language: AppLanguage)

    val spanishFirst: Flow<Boolean>
    suspend fun setSpanishFirst(enabled: Boolean)

    val spanishOnlySources: Flow<Boolean>
    suspend fun setSpanishOnlySources(enabled: Boolean)

    val subtitlePreference: Flow<SubtitlePreference>
    suspend fun setSubtitlePreference(preference: SubtitlePreference)

    val subtitleAppearance: Flow<SubtitleAppearance>
    suspend fun setSubtitleAppearance(appearance: SubtitleAppearance)

    val experimentalSubtitleSync: Flow<Boolean>
    suspend fun setExperimentalSubtitleSync(enabled: Boolean)

    val matchContentFrameRate: Flow<Boolean>
    suspend fun setMatchContentFrameRate(enabled: Boolean)

    val autoNextEpisode: Flow<Boolean>
    suspend fun setAutoNextEpisode(enabled: Boolean)

    val searchQwerty: Flow<Boolean>
    suspend fun setSearchQwerty(enabled: Boolean)

    val hiddenCategories: Flow<Set<String>>
    suspend fun setHiddenCategories(names: Set<String>)

    val homeRowItemLimit: Flow<Int>
    suspend fun setHomeRowItemLimit(limit: Int)

    val sourceProviders: Flow<Set<SourceProvider>>
    suspend fun setSourceProviders(providers: Set<SourceProvider>)

    val sourceExclusions: Flow<SourceExclusions>
    suspend fun setSourceExclusions(exclusions: SourceExclusions)

    /** Servicio debrid en uso (Real-Debrid por defecto). */
    val debridService: Flow<DebridService>
    suspend fun setDebridService(service: DebridService)

    /**
     * Audio por software (PCM): las extensiones (FFmpeg) van DELANTE del decodificador del sistema, así que
     * E-AC-3, AC-3, DTS y TrueHD se decodifican en la CPU a PCM. Quita el silencio en TVs estéreo o cuando el
     * sistema manda bitstream que la tele no sabe decodificar. Cuesta más CPU (sin Atmos/DTS:X).
     */
    val forceSoftwareAudio: Flow<Boolean>
    suspend fun setForceSoftwareAudio(enabled: Boolean)

    /** Reproducción: abrir el enlace resuelto en un reproductor externo (Just Player, VLC…) en vez del interno. */
    val useExternalPlayer: Flow<Boolean>
    suspend fun setUseExternalPlayer(enabled: Boolean)

    /**
     * Ajuste automático de frecuencia (AFR): adapta la tasa de refresco del televisor (24p, 50p, 60p) a los fotogramas
     * del vídeo en reproducción para eliminar el judder (tirones de 3:2 pulldown).
     */
    val autoFrameRate: Flow<Boolean>
    suspend fun setAutoFrameRate(enabled: Boolean)

    val relayUrl: Flow<String?>
    suspend fun setRelayUrl(url: String?)

    val relayPin: Flow<String?>
    suspend fun setRelayPin(pin: String?)

    val enableRelayAllDebrid: Flow<Boolean>
    suspend fun setEnableRelayAllDebrid(enabled: Boolean)

    val enableRelayRealDebrid: Flow<Boolean>
    suspend fun setEnableRelayRealDebrid(enabled: Boolean)

    val isRelayVip: Flow<Boolean>
    suspend fun setIsRelayVip(vip: Boolean)

    companion object {
        const val DEFAULT_HOME_ROW_ITEM_LIMIT = 30
        const val MIN_HOME_ROW_ITEM_LIMIT = 10
        const val MAX_HOME_ROW_ITEM_LIMIT = 100
        const val HOME_ROW_ITEM_LIMIT_STEP = 10
    }
}
