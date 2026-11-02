package com.shieldstream.tv.settings.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.UpdateRepository
import com.shieldstream.tv.domain.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Lo que pinta el aviso de versión nueva (al abrir la app). */
sealed interface UpdatePromptUiState {
    data object Hidden : UpdatePromptUiState
    data class Visible(val state: UpdateState) : UpdatePromptUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: UpdateRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** Estado completo (tarjeta de Ajustes). */
    val state: StateFlow<UpdateState> = updates.state

    /** El aviso sale con versión nueva (o descargando / instalando) mientras no se diga "Ahora no". */
    val prompt: StateFlow<UpdatePromptUiState> = combine(updates.state, updates.promptDismissed) { s, dismissed ->
        val relevant = when (s) {
            is UpdateState.Available, is UpdateState.Downloading,
            is UpdateState.NeedsPermission, is UpdateState.Installing -> true
            is UpdateState.Failed -> s.update != null
            else -> false
        }
        if (relevant && !dismissed) UpdatePromptUiState.Visible(s) else UpdatePromptUiState.Hidden
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatePromptUiState.Hidden)

    fun checkNow() = updates.check(manual = true)

    fun update() = updates.downloadAndInstall()

    fun later() = updates.dismissPrompt()

    /**
     * De vuelta en la app (tras la pantalla del permiso): si faltaba el permiso y ya está dado, se sigue solo
     * con la descarga; si no se concedió, se queda igual esperando al usuario.
     */
    fun onResumed() {
        if (updates.state.value !is UpdateState.NeedsPermission) return
        if (canRequestPackageInstalls()) updates.downloadAndInstall()
    }

    /** Android 8+ y permiso ya concedido (o Android viejo, que no lo necesita). */
    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < 26 || appContext.packageManager.canRequestPackageInstalls()

    /** Android 8+: pantalla del sistema "Instalar apps desconocidas" de Bambi. En algunas teles no existe. */
    fun openInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        // Cadena de respaldo: muchas TV (Fire OS, Google TV, Shield) no aceptan el URI "package:" y lanzan
        // ActivityNotFoundException con el primer intento; otras ni siquiera tienen la lista por app.
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_SECURITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (intent in intents) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
            Log.w(TAG, "Sin pantalla para ${intent.action} (${intent.data}): se prueba la siguiente")
        }
        Log.w(TAG, "Esta tele no tiene ninguna pantalla para dar el permiso de instalar")
        return false
    }

    private companion object {
        const val TAG = "Update"
    }
}
