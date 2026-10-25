package com.shieldstream.tv.ui.component

import android.content.Context
import android.content.Intent

/**
 * Reinicia la app (cambio de idioma). Se abre de nuevo la pantalla de inicio en una tarea limpia y se cierra el
 * proceso: así se vacían también las cachés en memoria (filas del Inicio, fichas…) que estaban en el idioma anterior.
 */
fun restartApp(context: Context) {
    val pm = context.packageManager
    val intent = (pm.getLeanbackLaunchIntentForPackage(context.packageName) ?: pm.getLaunchIntentForPackage(context.packageName))
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
