package com.shieldstream.tv.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.shieldstream.tv.model.tr

/**
 * Respuesta del instalador del sistema. Lo normal es STATUS_PENDING_USER_ACTION: se abre su ventana de "Instalar".
 * Si se instala, Android cierra Bambi y la siguiente vez arranca la versión nueva (con todos sus datos).
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { onFailure?.invoke(PackageInstaller.STATUS_FAILURE, tr("No se pudo abrir el instalador.", "Couldn't open the installer.")) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Update", "Actualización instalada")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Update", "Instalación fallida ($status): $message")
                // Cancelar en la ventana del sistema también llega aquí (STATUS_FAILURE_ABORTED).
                onFailure?.invoke(
                    status,
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                        tr("Instalación cancelada.", "Installation cancelled.")
                    } else {
                        tr("No se pudo instalar la actualización.", "Couldn't install the update.")
                    },
                )
            }
        }
    }

    companion object {
        /** Lo pone UpdateRepositoryImpl para enterarse de los fallos (la app sigue abierta): (estado, mensaje). */
        @Volatile
        var onFailure: ((Int, String) -> Unit)? = null
    }
}
