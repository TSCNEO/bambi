package com.shieldstream.tv.domain

import kotlinx.coroutines.flow.StateFlow

/** Versión instalada y de dónde salen las actualizaciones (lo da el módulo app desde BuildConfig). */
data class AppVersion(
    val name: String,
    /** "Bambi (pruebas)": no se auto-actualiza (es otra app, con otro paquete). */
    val isDebug: Boolean,
    /** Repositorio de GitHub con las releases, "usuario/repo". */
    val updateRepo: String,
    /** Versión base del repositorio upstream (grodri99/bambi) sobre la que va este fork. "" = desconocida. */
    val upstreamVersion: String = "",
)

/** Versión publicada en GitHub. */
data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Estado de la actualización. Lo pintan el aviso al abrir la app y la tarjeta de Ajustes. */
sealed interface UpdateState {
    /** Build de pruebas: no se actualiza sola. */
    data object Unsupported : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val current: String, val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    /** Android pide permitir a Bambi instalar apps (solo la primera vez). */
    data class NeedsPermission(val update: AppUpdate) : UpdateState
    /** APK descargado y entregado al instalador del sistema: falta pulsar "Instalar". */
    data class Installing(val update: AppUpdate) : UpdateState
    data class Failed(val message: String, val update: AppUpdate?) : UpdateState
}

/**
 * Actualizaciones desde las releases de GitHub. Android no deja que una app normal se instale sola en silencio:
 * lo máximo es descargar y abrir el instalador del sistema, que pide un único "Instalar".
 */
interface UpdateRepository {
    val state: StateFlow<UpdateState>

    /** El usuario dijo "Ahora no": el aviso no vuelve a salir hasta el próximo arranque. */
    val promptDismissed: StateFlow<Boolean>

    /** [manual] = desde Ajustes (siempre consulta); si no, como mucho una vez cada pocas horas. */
    fun check(manual: Boolean)

    fun downloadAndInstall()

    fun dismissPrompt()
}
