package com.shieldstream.tv.domain

/**
 * Valida el token de Real-Debrid y lo persiste de forma segura.
 */
class ValidateTokenUseCase(
    private val realDebrid: RealDebridRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(token: String): Boolean {
        val valid = realDebrid.validateToken(token)
        if (valid) {
            settings.setToken(token)
        }
        return valid
    }
}
