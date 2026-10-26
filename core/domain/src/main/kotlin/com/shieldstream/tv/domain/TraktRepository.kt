package com.shieldstream.tv.domain

/**
 * Datos de la aplicación "Bambi" registrada en trakt.tv (no son de ningún usuario). Vienen de local.properties
 * (traktClientId y, opcional, traktClientSecret) a través de BuildConfig. Sin Client ID = Trakt no disponible.
 *
 * Desde oct-2026 Trakt ya no da Client Secret a las apps nuevas (PKCE); el enlace con código de la tele funciona
 * solo con el Client ID. Si hay secreto (apps antiguas) se envía igualmente.
 */
data class TraktCredentials(
    val clientId: String,
    val clientSecret: String = "",
    /** Redirect URI registrada en la app de Trakt (traktRedirectUri). Solo se usa al renovar el token. */
    val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
}

/** Código para enlazar la cuenta: se escribe en [verificationUrl] desde el móvil. */
data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** Resultado de preguntar a Trakt si el usuario ya ha escrito el código. */
sealed interface TraktAuthPoll {
    data object Pending : TraktAuthPoll
    /** Trakt pide preguntar más despacio. */
    data object SlowDown : TraktAuthPoll
    data class Connected(val username: String) : TraktAuthPoll
    data object Expired : TraktAuthPoll
    data object Denied : TraktAuthPoll
    data class Failed(val message: String) : TraktAuthPoll
}

enum class ScrobbleAction { START, PAUSE, STOP }

/**
 * Trakt por perfil: enlazar la cuenta con un código (como Netflix en la tele) y sincronizar el progreso.
 *
 *  - Al reproducir: start / pause / stop (scrobble). Trakt guarda por dónde vas y, pasado el 80 %, lo marca visto.
 *  - Al elegir perfil o volver a la app: se baja lo de Trakt ("Seguir viendo" y vistos de la otra Shield).
 *  - Al enlazar: se sube lo que ya había en esta Shield.
 *
 * Todo es "en segundo plano": si Trakt no responde, la app sigue funcionando con su progreso local.
 */
interface TraktRepository {
    val isConfigured: Boolean

    suspend fun requestDeviceCode(): Result<TraktDeviceCode>

    /** Una consulta. Si conecta, guarda los tokens en el perfil y lanza la primera sincronización. */
    suspend fun pollDeviceToken(profileId: String, code: TraktDeviceCode): TraktAuthPoll

    /** Desenlaza la cuenta del perfil (revoca el token en Trakt si se puede). */
    suspend fun disconnect(profileId: String)

    /** Baja de Trakt el progreso del perfil activo. [force] = aunque se haya sincronizado hace poco. */
    fun syncActive(force: Boolean = false)

    /**
     * Avisa a Trakt de lo que se está viendo. [progressKey] es la clave de WatchProgress ("movie:ID" o
     * "tv:ID:T:E", ids de TMDB) y [fraction] cuánto va (0..1). Solo si el perfil activo tiene Trakt.
     */
    fun scrobble(progressKey: String, fraction: Float, action: ScrobbleAction)

    /**
     * Marcado a mano en la ficha (claves de WatchProgress): [watched] = al historial de Trakt (y fuera de "a medias");
     * si no, se quita del historial. Llamar ANTES de guardar el cambio local. Solo si el perfil activo tiene Trakt.
     */
    fun setWatched(progressKeys: Collection<String>, watched: Boolean)
}
