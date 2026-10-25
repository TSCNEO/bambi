package com.shieldstream.tv.model

import java.util.Locale

/**
 * Idioma de la interfaz de Bambi. Se elige en Ajustes (o en la guía de configuración).
 *
 * Funcionamiento: se lee UNA vez al arrancar (App.onCreate, antes que nada) y al cambiarlo la app se reinicia.
 * Así cualquier texto (también los de enums, filas ya cargadas, mensajes de error de los repositorios…) sale en
 * el idioma nuevo sin tener que avisar a cada pantalla.
 *
 * [tmdb]: idioma de títulos y sinopsis de TMDB. [region]: país para estrenos y edades recomendadas.
 */
enum class AppLanguage(val code: String, val tmdb: String, val region: String, val label: String) {
    ES("es", "es-ES", "ES", "Español"),
    EN("en", "en-US", "US", "English"),
    ;

    val locale: Locale get() = Locale(code, region)

    companion object {
        /** SharedPreferences y clave donde se guarda (las lee App.onCreate antes que nada). */
        const val PREFS = "settings"
        const val PREF_KEY = "app_language"

        /** Primera vez (sin elegir): español si el sistema está en español; si no, inglés. */
        fun default(): AppLanguage = if (Locale.getDefault().language == "es") ES else EN

        fun fromName(name: String?): AppLanguage? = entries.firstOrNull { it.name == name }
    }
}

object Lang {
    @Volatile
    var current: AppLanguage = AppLanguage.ES

    val isEnglish: Boolean get() = current == AppLanguage.EN

    /** Formato de números y mayúsculas según el idioma (0,5 / 0.5…). */
    val locale: Locale get() = current.locale
}

/** Texto en el idioma de la app. Uso: tr("Reproducir", "Play"). */
fun tr(es: String, en: String): String = if (Lang.current == AppLanguage.EN) en else es
