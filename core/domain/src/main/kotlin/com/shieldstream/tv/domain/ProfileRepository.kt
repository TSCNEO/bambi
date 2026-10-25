package com.shieldstream.tv.domain

import com.shieldstream.tv.model.Profile
import kotlinx.coroutines.flow.StateFlow

/**
 * Perfiles de la app. Siempre hay al menos uno: la primera vez se crea [Profile.DEFAULT_ID] con el progreso que
 * ya existía. [active] es el elegido en "¿Quién está viendo?" (y el último usado al arrancar).
 */
interface ProfileRepository {
    val profiles: StateFlow<List<Profile>>
    val active: StateFlow<Profile>

    /** Cambia de perfil: el progreso, "Seguir viendo" y Trakt pasan a ser los suyos. */
    fun select(id: String)

    /** Crea (id nuevo) o actualiza un perfil. Devuelve el perfil guardado. */
    fun save(profile: Profile): Profile

    /** Perfil nuevo, aún sin guardar, con id único y un color que no use nadie si se puede. */
    fun draft(): Profile

    /** Borra el perfil y todo lo suyo (progreso, Trakt). No borra el último que quede. */
    fun delete(id: String)
}
