package com.shieldstream.tv.settings

import com.shieldstream.tv.domain.SourceProvider
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.tr
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.ApiKeysRepository
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.domain.KeyCheck
import com.shieldstream.tv.domain.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de la clave de un servicio. */
sealed interface ServiceStatus {
    data object Missing : ServiceStatus
    data object Checking : ServiceStatus
    data class Ok(val detail: String?) : ServiceStatus
    data class Bad(val message: String) : ServiceStatus
}

data class ServiceUi(
    val service: ApiService,
    /** Últimos 4 caracteres a la vista, el resto con puntos. null = sin clave. */
    val maskedKey: String?,
    val status: ServiceStatus,
)

/** Ventana para escribir una clave nueva. */
data class KeyEditor(
    val service: ApiService,
    val hasKey: Boolean,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Ready(
        val services: List<ServiceUi>,
        val bufferSeconds: Int,
        /** ◀▶ en el reproductor: miniaturas (true) o saltos de 10 s (false). */
        val seekThumbnails: Boolean,
        val forceSoftwareAudio: Boolean,
        val useExternalPlayer: Boolean,
        val autoFrameRate: Boolean,
        /** Servicio debrid en uso. */
        val debrid: DebridService,
        val relayUrl: String?,
        val relayPin: String?,
        val enableRelayAllDebrid: Boolean,
        val enableRelayRealDebrid: Boolean,
        val editor: KeyEditor?,
        /** Idioma con el que arrancó la app (cambiarlo la reinicia). */
        val language: AppLanguage = Lang.current,
    ) : SettingsUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeys: ApiKeysRepository,
    private val settings: SettingsRepository,
    private val customCatalog: com.shieldstream.tv.domain.CustomCatalogRepository,
    private val appVersion: com.shieldstream.tv.domain.AppVersion,
) : ViewModel() {

    /** Versión base de grodri99/bambi sobre la que va este fork (vacío = no se muestra). */
    val upstreamVersion: String get() = appVersion.upstreamVersion


    val isCustomCatalogActivated: Boolean get() = customCatalog.isActivated()
    fun getCatalogVersion(): String? = customCatalog.getCatalogVersion()
    suspend fun pairCustomCatalog(pin: String): Boolean = customCatalog.pairWithPin(pin)
    suspend fun syncCustomCatalog(): com.shieldstream.tv.domain.CustomCatalogRepository.SyncResult = customCatalog.forceUpdate()

    private val statuses = MutableStateFlow<Map<ApiService, ServiceStatus>>(emptyMap())
    private val editor = MutableStateFlow<KeyEditor?>(null)

    private val playback = combine(
        combine(
            settings.maxBufferSeconds,
            settings.seekThumbnails,
            settings.debridService,
        ) { buffer, thumbnails, debrid ->
            Triple(buffer, thumbnails, debrid)
        },
        combine(
            settings.forceSoftwareAudio,
            settings.useExternalPlayer,
            settings.autoFrameRate,
        ) { forceSoftware, useExternal, afr ->
            Triple(forceSoftware, useExternal, afr)
        },
        combine(
            settings.relayUrl,
            settings.relayPin,
            settings.enableRelayAllDebrid,
            settings.enableRelayRealDebrid,
        ) { url, pin, ad, rd ->
            RelaySettings(url, pin, ad, rd)
        },
    ) { (buffer, thumbnails, debrid), (forceSoftware, useExternal, afr), relay ->
        SettingsPlayback(buffer, thumbnails, debrid, forceSoftware, useExternal, afr, relay.url, relay.pin, relay.ad, relay.rd)
    }

data class RelaySettings(
    val url: String?,
    val pin: String?,
    val ad: Boolean,
    val rd: Boolean,
)

data class SettingsPlayback(
    val bufferSeconds: Int,
    val seekThumbnails: Boolean,
    val debrid: DebridService,
    val forceSoftwareAudio: Boolean,
    val useExternalPlayer: Boolean,
    val autoFrameRate: Boolean,
    val relayUrl: String?,
    val relayPin: String?,
    val enableRelayAllDebrid: Boolean,
    val enableRelayRealDebrid: Boolean,
)

    val uiState: StateFlow<SettingsUiState> = combine(
        apiKeys.keys,
        statuses,
        playback,
        editor,
    ) { keys, status, play, edit ->
        SettingsUiState.Ready(
            services = ApiService.entries.map { service ->
                val key = keys[service]
                ServiceUi(
                    service = service,
                    maskedKey = key?.let(::mask),
                    status = if (key.isNullOrBlank()) ServiceStatus.Missing else status[service] ?: ServiceStatus.Checking,
                )
            },
            bufferSeconds = play.bufferSeconds,
            seekThumbnails = play.seekThumbnails,
            forceSoftwareAudio = play.forceSoftwareAudio,
            useExternalPlayer = play.useExternalPlayer,
            autoFrameRate = play.autoFrameRate,
            debrid = play.debrid,
            relayUrl = play.relayUrl,
            relayPin = play.relayPin,
            enableRelayAllDebrid = play.enableRelayAllDebrid,
            enableRelayRealDebrid = play.enableRelayRealDebrid,
            editor = edit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState.Loading)

    init {
        // Al entrar se prueban todas las claves guardadas (en paralelo) para enseñar su estado real.
        apiKeys.keys.value.forEach { (service, key) ->
            if (!key.isNullOrBlank()) {
                viewModelScope.launch {
                    val result = apiKeys.check(service, key).toStatus()
                    statuses.update { it + (service to result) }
                }
            }
        }
    }

    /** Guarda el idioma y avisa para reiniciar la app (lo hace la pantalla, que tiene el Context). */
    fun setLanguage(language: AppLanguage, onSaved: () -> Unit) {
        if (language == settings.language) return
        viewModelScope.launch {
            settings.setLanguage(language)
            onSaved()
        }
    }

    /** Buscadores de fuentes activados (se pinta aparte del resto del estado). */
    val providers: StateFlow<Set<SourceProvider>> =
        settings.sourceProviders.stateIn(viewModelScope, SharingStarted.Eagerly, SourceProvider.entries.toSet())

    /** Activa o desactiva un buscador; siempre queda al menos uno. */
    fun toggleProvider(p: SourceProvider) {
        viewModelScope.launch {
            val current = settings.sourceProviders.first()
            // Toda la sección a la vez (los buscadores propios van juntos bajo "The Pirate Bay").
            val group = SourceProvider.entries.filter { it.section == p.section }.toSet()
            val next = if (group.all { it in current }) current - group else current + group
            if (next.isNotEmpty()) settings.setSourceProviders(next)
        }
    }

    fun openEditor(service: ApiService) {
        editor.value = KeyEditor(service, hasKey = !apiKeys.keys.value[service].isNullOrBlank())
    }

    fun closeEditor() {
        if (editor.value?.busy != true) editor.value = null
    }

    /** Solo se guarda si el servicio la acepta: así nunca se queda una clave rota guardada. */
    fun saveKey(input: String) {
        val current = editor.value ?: return
        val key = input.trim()
        if (key.isEmpty()) {
            editor.value = current.copy(error = tr("Escribe la clave primero", "Type the key first"))
            return
        }
        editor.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = apiKeys.check(current.service, key)) {
                is KeyCheck.Valid -> {
                    apiKeys.set(current.service, key)
                    statuses.update { it + (current.service to ServiceStatus.Ok(result.detail)) }
                    editor.value = null
                }
                is KeyCheck.Invalid -> editor.value = current.copy(busy = false, error = result.message)
            }
        }
    }

    /** Solo para servicios opcionales (SubDL). */
    fun removeKey() {
        val current = editor.value ?: return
        if (current.service.required) return
        viewModelScope.launch {
            apiKeys.set(current.service, null)
            statuses.update { it - current.service }
            editor.value = null
        }
    }

    fun setDebrid(service: DebridService) {
        viewModelScope.launch { settings.setDebridService(service) }
    }

    /** "Castellano primero" (aparte del UiState principal: no cambia nada más de la pantalla). */
    val spanishFirst: StateFlow<Boolean> = settings.spanishFirst
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSpanishFirst(enabled: Boolean) {
        viewModelScope.launch { settings.setSpanishFirst(enabled) }
    }

    fun setForceSoftwareAudio(enabled: Boolean) {
        viewModelScope.launch { settings.setForceSoftwareAudio(enabled) }
    }

    fun setUseExternalPlayer(enabled: Boolean) {
        viewModelScope.launch { settings.setUseExternalPlayer(enabled) }
    }

    fun setAutoFrameRate(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoFrameRate(enabled) }
    }

    /** Series: siguiente episodio automático. */
    val autoNextEpisode: StateFlow<Boolean> = settings.autoNextEpisode
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoNextEpisode(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoNextEpisode(enabled) }
    }

    /** Categorías del Inicio ocultas (nombres). */
    val hiddenCategories: StateFlow<Set<String>> = settings.hiddenCategories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Muestra u oculta una categoría. Nunca se ocultan todas: la última visible no se deja quitar. */
    fun toggleCategory(category: com.shieldstream.tv.model.Category) {
        val hidden = hiddenCategories.value
        val next = if (category.name in hidden) {
            hidden - category.name
        } else {
            val visibleLeft = com.shieldstream.tv.model.Category.entries.count { it.name !in hidden && it != category }
            if (visibleLeft == 0) return
            hidden + category.name
        }
        viewModelScope.launch { settings.setHiddenCategories(next) }
    }

    fun showAllCategories() {
        viewModelScope.launch { settings.setHiddenCategories(emptySet()) }
    }

    fun setRelayUrl(url: String?) {
        viewModelScope.launch { settings.setRelayUrl(url) }
    }

    fun setRelayPin(pin: String?) {
        viewModelScope.launch { settings.setRelayPin(pin) }
    }

    fun setEnableRelayAllDebrid(enabled: Boolean) {
        viewModelScope.launch { settings.setEnableRelayAllDebrid(enabled) }
    }

    fun setEnableRelayRealDebrid(enabled: Boolean) {
        viewModelScope.launch { settings.setEnableRelayRealDebrid(enabled) }
    }

    fun setSeekThumbnails(enabled: Boolean) {
        viewModelScope.launch { settings.setSeekThumbnails(enabled) }
    }

    fun changeBuffer(deltaSeconds: Int) {
        viewModelScope.launch {
            val now = settings.maxBufferSeconds.first()
            settings.setMaxBufferSeconds(now + deltaSeconds)
        }
    }

    private fun KeyCheck.toStatus(): ServiceStatus = when (this) {
        is KeyCheck.Valid -> ServiceStatus.Ok(detail)
        is KeyCheck.Invalid -> ServiceStatus.Bad(message)
    }

    private fun mask(key: String): String = if (key.length <= 4) "••••" else "••••••••  " + key.takeLast(4)
}
