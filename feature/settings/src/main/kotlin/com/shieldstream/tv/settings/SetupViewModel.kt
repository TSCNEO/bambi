package com.shieldstream.tv.settings

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
}

/** Decide si al arrancar hay que abrir la guía: sin clave de TMDB o sin ningún debrid configurado. */
@HiltViewModel
class SetupGateViewModel @Inject constructor(apiKeys: ApiKeysRepository) : ViewModel() {
    val needsSetup: Boolean = apiKeys.keys.value.let { keys ->
        keys[ApiService.TMDB].isNullOrBlank() ||
            ApiService.entries.filter { it.isDebrid }.all { keys[it].isNullOrBlank() }
    }
}
