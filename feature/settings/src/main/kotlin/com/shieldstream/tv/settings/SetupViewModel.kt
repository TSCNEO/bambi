package com.shieldstream.tv.settings

import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.tr
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
import java.io.IOException
import javax.inject.Inject

/** Pasos de la guía de configuración inicial. */
enum class SetupStep(val number: Int?) {
    WELCOME(null),
    TMDB(1),
    DEBRID(2),
    SUBDL(3),
    DONE(null),
}

sealed interface SetupUiState {
    data object Loading : SetupUiState
    data class Ready(
        val step: SetupStep,
        val debrid: DebridService,
        /** Clave actual de cada servicio (null = sin configurar). */
        val keys: Map<ApiService, String?>,
        /** Lo que respondió cada servicio al comprobar su clave (usuario, días de Premium…). */
        val details: Map<ApiService, String>,
        /** Comprobando una clave con su servicio. */
        val checking: Boolean,
        /** Mensaje de error del paso actual (clave no válida, sin conexión…). */
        val error: String?,
    ) : SetupUiState {
        fun has(service: ApiService) = !keys[service].isNullOrBlank()
    }
}

/**
 * Guía de configuración: la primera vez (sin claves) se abre antes del Inicio; también desde Ajustes.
 * Cada clave se comprueba con su servicio antes de guardarse (ApiKeysRepository.check), como en Ajustes.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val apiKeys: ApiKeysRepository,
    private val settings: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val customCatalog: CustomCatalogRepository,
) : ViewModel() {

    private val step = MutableStateFlow(SetupStep.WELCOME)
    private val details = MutableStateFlow<Map<ApiService, String>>(emptyMap())
    private val checking = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val status = combine(details, checking, error) { d, c, e -> Triple(d, c, e) }

    val uiState: StateFlow<SetupUiState> = combine(
        step,
        settings.debridService,
        apiKeys.keys,
        status,
    ) { s, debrid, keys, (d, c, e) ->
        SetupUiState.Ready(step = s, debrid = debrid, keys = keys, details = d, checking = c, error = e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState.Loading)

    fun next() {
        val current = step.value
        step.value = SetupStep.entries.getOrElse(current.ordinal + 1) { SetupStep.DONE }
        error.value = null
    }

    /** false = ya estaba en el primer paso (la pantalla decide si sale). */
    fun back(): Boolean {
        if (checking.value) return true
        val current = step.value
        if (current == SetupStep.WELCOME) return false
        step.value = SetupStep.entries[current.ordinal - 1]
        error.value = null
        return true
    }

    /** Cambia el idioma desde la bienvenida; la pantalla reinicia la app (vuelve a abrirse la guía). */
    fun setLanguage(language: AppLanguage, onSaved: () -> Unit) {
        viewModelScope.launch {
            settings.setLanguage(language)
            onSaved()
        }
    }

    fun setDebrid(service: DebridService) {
        error.value = null
        viewModelScope.launch { settings.setDebridService(service) }
    }

    /** Comprueba la clave con su servicio y, si vale, la guarda. */
    fun submitKey(service: ApiService, input: String) {
        val key = input.trim()
        if (key.isEmpty()) {
            error.value = tr("Escribe la clave primero (pulsa OK sobre el recuadro para abrir el teclado).", "Type the key first (press OK on the box to open the keyboard).")
            return
        }
        if (checking.value) return
        checking.value = true
        error.value = null
        viewModelScope.launch {
            when (val result = apiKeys.check(service, key)) {
                is KeyCheck.Valid -> {
                    apiKeys.set(service, key)
                    details.update { it + (service to (result.detail ?: tr("Conectado", "Connected"))) }
                }
                is KeyCheck.Invalid -> error.value = result.message
            }
            checking.value = false
        }
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
        val url = urlInput.trim().trimEnd('/')
        val pin = pinInput.trim()
        if (url.isEmpty()) {
            error.value = tr("Escribe la URL del servidor", "Type the server URL")
            return
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            error.value = tr("La URL debe empezar por http:// o https://", "The URL must start with http:// or https://")
            return
        }
        if (pin.length != 6 || pin.any { !it.isDigit() }) {
            error.value = tr("El PIN debe tener 6 cifras", "The PIN must have 6 digits")
            return
        }
        if (checking.value) return
        checking.value = true
        error.value = null

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$url/api/v1/client/config")
                    .header("X-Relay-Token", pin)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string().orEmpty()
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
                                error.value = tr("El servidor respondió con error ${response.code}", "Server responded with error ${response.code}")
                            }
                        }
                    }
                }
            }.getOrElse { e ->
                withContext(Dispatchers.Main) {
                    checking.value = false
                    error.value = when {
                        e is IOException -> tr("No se pudo conectar con el servidor. Revisa la red y la URL.", "Could not connect to server. Check network and URL.")
                        else -> e.message ?: tr("Error de conexión", "Connection error")
                    }
                }
            }
        }
    }
}

/** Decide si al arrancar hay que abrir la guía: sin clave de TMDB o sin ningún debrid configurado. */
@HiltViewModel
class SetupGateViewModel @Inject constructor(apiKeys: ApiKeysRepository) : ViewModel() {
    val needsSetup: Boolean = apiKeys.keys.value.let { keys ->
        keys[ApiService.TMDB].isNullOrBlank() ||
            ApiService.entries.filter { it.isDebrid }.all { keys[it].isNullOrBlank() }
    }
}
