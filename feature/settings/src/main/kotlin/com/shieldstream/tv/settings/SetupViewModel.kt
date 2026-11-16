package com.shieldstream.tv.settings

import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.SubtitlePreference
import com.shieldstream.tv.model.tr
import com.shieldstream.tv.strings.Texts
import com.shieldstream.tv.strings.R.string as StringResources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.ApiKeysRepository
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.CustomCatalogRepository
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.domain.KeyCheck
import com.shieldstream.tv.domain.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject

enum class SetupStep(val number: Int?) {
    WELCOME(null),
    TMDB(1),
    DEBRID(2),
    SUBDL(3),
    PLAYBACK(4),
    DONE(null),
}

enum class SetupAudioPreference { ORIGINAL, SPANISH, SPANISH_ONLY }

private data class SetupPlaybackPreferences(
    val language: AppLanguage,
    val spanishFirst: Boolean,
    val spanishOnly: Boolean,
    val subtitles: SubtitlePreference,
    val experimentalSubtitleSync: Boolean,
)

sealed interface SetupUiState {
    data object Loading : SetupUiState
    data class Ready(
        val step: SetupStep,
        val debrid: DebridService,
        val keys: Map<ApiService, String?>,
        val details: Map<ApiService, String>,
        val checking: Boolean,
        val error: String?,
        val audio: SetupAudioPreference,
        val subtitles: SubtitlePreference,
        val experimentalSubtitleSync: Boolean = true,
        val language: AppLanguage,
    ) : SetupUiState {
        val languageChanged: Boolean get() = language != com.shieldstream.tv.model.Lang.current
        fun has(service: ApiService) = !keys[service].isNullOrBlank()
    }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val apiKeys: ApiKeysRepository,
    private val settings: SettingsRepository,
    private val texts: Texts,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val customCatalog: CustomCatalogRepository,
) : ViewModel() {

    private val step = MutableStateFlow(SetupStep.WELCOME)
    private val selectedLanguage = MutableStateFlow(settings.language)
    private val details = MutableStateFlow<Map<ApiService, String>>(emptyMap())
    private val checking = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val status = combine(details, checking, error) { d, c, e -> Triple(d, c, e) }
    private val playback = combine(
        selectedLanguage,
        settings.spanishFirst,
        settings.spanishOnlySources,
        settings.subtitlePreference,
        settings.experimentalSubtitleSync,
        ::SetupPlaybackPreferences,
    )

    val uiState: StateFlow<SetupUiState> = combine(
        step,
        settings.debridService,
        apiKeys.keys,
        status,
        playback,
    ) { s, debrid, keys, (d, c, e), preferences ->
        SetupUiState.Ready(
            step = s,
            debrid = debrid,
            keys = keys,
            details = d,
            checking = c,
            error = e,
            audio = when {
                preferences.spanishOnly -> SetupAudioPreference.SPANISH_ONLY
                preferences.spanishFirst -> SetupAudioPreference.SPANISH
                else -> SetupAudioPreference.ORIGINAL
            },
            subtitles = preferences.subtitles,
            experimentalSubtitleSync = preferences.experimentalSubtitleSync,
            language = preferences.language,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState.Loading)

    fun next() {
        val current = step.value
        step.value = SetupStep.entries.getOrElse(current.ordinal + 1) { SetupStep.DONE }
        error.value = null
    }

    fun back(): Boolean {
        if (checking.value) return true
        val current = step.value
        if (current == SetupStep.WELCOME) return false
        step.value = SetupStep.entries[current.ordinal - 1]
        error.value = null
        return true
    }

    fun setLanguage(language: AppLanguage) {
        selectedLanguage.value = language
        viewModelScope.launch { settings.setLanguage(language) }
    }

    fun setDebrid(service: DebridService) {
        error.value = null
        viewModelScope.launch { settings.setDebridService(service) }
    }

    fun setAudio(preference: SetupAudioPreference) {
        viewModelScope.launch {
            settings.setSpanishOnlySources(preference == SetupAudioPreference.SPANISH_ONLY)
            settings.setSpanishFirst(preference != SetupAudioPreference.ORIGINAL)
        }
    }

    fun setSubtitles(preference: SubtitlePreference) {
        viewModelScope.launch { settings.setSubtitlePreference(preference) }
    }

    fun setExperimentalSubtitleSync(enabled: Boolean) {
        viewModelScope.launch { settings.setExperimentalSubtitleSync(enabled) }
    }

    fun submitKey(service: ApiService, input: String) {
        val key = input.trim()
        if (key.isEmpty()) {
            error.value = texts.get(StringResources.setup_key_required)
            return
        }
        if (checking.value) return
        checking.value = true
        error.value = null
        viewModelScope.launch {
            when (val result = apiKeys.check(service, key)) {
                is KeyCheck.Valid -> {
                    apiKeys.set(service, key)
                    details.update { it + (service to (result.detail ?: texts.get(StringResources.setup_connected))) }
                }
                is KeyCheck.Invalid -> error.value = result.message
            }
            checking.value = false
        }
    }

    fun completeAllDebridConnect() {
        error.value = null
        details.update { it + (ApiService.ALLDEBRID to texts.get(StringResources.setup_connected)) }
    }

    fun clearError() {
        error.value = null
    }

    // ------------------------------------------------------------------ vincular desde el móvil

    private var pairingServer: LocalPairingServer? = null

    /** URL del formulario local que se enseña en el QR (null = servidor apagado). */
    private val pairingUrl = MutableStateFlow<String?>(null)

    val localPairingUrl: StateFlow<String?> = pairingUrl

    /**
     * Abre un servidor HTTP efímero en la TV para que el móvil (misma Wi-Fi) mande
     * URL y PIN sin tocar el mando. Al recibirlos se conecta igual que a mano.
     */
    fun startLocalPairing(
        onQrReady: (String) -> Unit,
        onVipSuccess: () -> Unit,
        onStandardSuccess: () -> Unit,
    ) {
        stopLocalPairing()
        pairingUrl.value = null
        val server = LocalPairingServer(
            onCredentials = { url, pin ->
                // Hilo de fondo del servidor: connectRelay se encarga de volver a Main.
                connectRelay(
                    urlInput = url,
                    pinInput = pin,
                    onVipSuccess = onVipSuccess,
                    onStandardSuccess = onStandardSuccess,
                )
            },
            onReady = { url ->
                pairingUrl.value = url
                withContextMain { onQrReady(url) }
            },
            onError = { message ->
                pairingUrl.value = null
                withContextMain { error.value = message }
            },
        )
        pairingServer = server
        server.start()
    }

    /** Cierra el servidor local si estaba abierto. */
    fun stopLocalPairing() {
        pairingServer?.stop()
        pairingServer = null
        // La última URL se queda para que el QR no parpadee mientras el móvil ya la está usando.
    }

    private fun withContextMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    override fun onCleared() {
        stopLocalPairing()
        super.onCleared()
    }

    fun connectRelay(
        urlInput: String,
        pinInput: String,
        onVipSuccess: () -> Unit,
        onStandardSuccess: () -> Unit,
    ) {
        val rawUrl = urlInput.trim().trimEnd('/')
        val pin = pinInput.trim()
        if (rawUrl.isEmpty()) {
            error.value = tr("Escribe la URL del servidor", "Type the server URL")
            return
        }
        if (pin.isEmpty()) {
            error.value = tr("Escribe el PIN", "Type the PIN")
            return
        }
        if (checking.value) return
        checking.value = true
        error.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val candidates = if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                listOf(rawUrl)
            } else {
                listOf("https://$rawUrl", "http://$rawUrl")
            }

            var lastError: Throwable? = null
            var successfulResponse: Pair<String, Response>? = null

            for (candidate in candidates) {
                try {
                    val request = Request.Builder()
                        .url("$candidate/api/v1/client/config")
                        .header("X-Relay-Token", pin)
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    successfulResponse = candidate to response
                    break
                } catch (e: Exception) {
                    lastError = e
                    // Si falla https y hay alternativa (http), prueba la siguiente
                }
            }

            if (successfulResponse == null) {
                withContext(Dispatchers.Main) {
                    checking.value = false
                    val e = lastError
                    error.value = when {
                        e is IOException -> tr(
                            "No se pudo conectar con el servidor. Revisa la red y la URL.",
                            "Could not connect to server. Check network and URL.",
                        )
                        else -> e?.message ?: tr("Error de conexión", "Connection error")
                    }
                }
                return@launch
            }

            val (url, response) = successfulResponse
            response.use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        val jsonElement = json.parseToJsonElement(body).jsonObject
                        val isVip = jsonElement["is_vip"]?.jsonPrimitive?.booleanOrNull == true
                        if (isVip) {
                            val debridServiceStr = jsonElement["debrid_service"]?.jsonPrimitive?.contentOrNull ?: "alldebrid"
                            val debridApiKey = jsonElement["debrid_api_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val tmdbApiKey = jsonElement["tmdb_api_key"]?.jsonPrimitive?.contentOrNull.orEmpty()

                            val debridService = DebridService.entries.firstOrNull {
                                it.name.equals(debridServiceStr, ignoreCase = true) ||
                                it.torrentioKey.equals(debridServiceStr, ignoreCase = true) ||
                                it.shortName.equals(debridServiceStr, ignoreCase = true)
                            } ?: DebridService.ALLDEBRID

                            val isAllDebrid = debridService == DebridService.ALLDEBRID || debridService.torrentioKey.equals("alldebrid", ignoreCase = true)

                            settings.setRelayUrl(url)
                            settings.setRelayPin(pin)
                            settings.setIsRelayVip(true)
                            settings.setDebridService(debridService)
                            settings.setEnableRelayAllDebrid(isAllDebrid)
                            settings.setEnableRelayRealDebrid(!isAllDebrid)
                            if (tmdbApiKey.isNotBlank()) {
                                apiKeys.set(ApiService.TMDB, tmdbApiKey)
                            }
                            if (debridApiKey.isNotBlank()) {
                                apiKeys.set(debridService.apiService, debridApiKey)
                            }

                            // Catálogo privado: si el servidor manda un PIN Moria, se vincula solo.
                            val moriaPin = jsonElement["moria_pin"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            if (moriaPin.isNotBlank()) {
                                runCatching { customCatalog.pairWithPin(moriaPin) }
                            }

                            withContext(Dispatchers.Main) {
                                checking.value = false
                                onVipSuccess()
                            }
                        } else {
                            settings.setRelayUrl(url)
                            settings.setRelayPin(pin)
                            settings.setIsRelayVip(false)
                            withContext(Dispatchers.Main) {
                                checking.value = false
                                onStandardSuccess()
                            }
                        }
                    }
                    401, 403 -> {
                        withContext(Dispatchers.Main) {
                            checking.value = false
                            error.value = tr("PIN no válido o expirado", "Invalid or expired PIN")
                        }
                    }
                    429 -> {
                        withContext(Dispatchers.Main) {
                            checking.value = false
                            error.value = tr("Demasiados intentos. IP bloqueada temporalmente.", "Too many requests. IP temporarily blocked.")
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            checking.value = false
                            error.value = tr("El servidor respondió con error ${resp.code}", "Server responded with error ${resp.code}")
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class SetupGateViewModel @Inject constructor(apiKeys: ApiKeysRepository) : ViewModel() {
    val needsSetup: Boolean = apiKeys.keys.value.let { keys ->
        keys[ApiService.TMDB].isNullOrBlank() ||
            ApiService.entries.filter { it.isDebrid }.all { keys[it].isNullOrBlank() }
    }
}
