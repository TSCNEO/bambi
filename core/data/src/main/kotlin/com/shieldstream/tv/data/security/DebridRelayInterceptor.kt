package com.shieldstream.tv.data.security

import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.tr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridRelayInterceptor @Inject constructor(
    private val settings: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host.lowercase()

        val isAllDebrid = host.contains("alldebrid.com")
        val isRealDebrid = host.contains("real-debrid.com")

        if (!isAllDebrid && !isRealDebrid) {
            return chain.proceed(request)
        }

        val enableAd = runBlocking { settings.enableRelayAllDebrid.first() }
        val enableRd = runBlocking { settings.enableRelayRealDebrid.first() }

        val active = (isAllDebrid && enableAd) || (isRealDebrid && enableRd)
        if (!active) {
            return chain.proceed(request)
        }

        val relayUrl = runBlocking { settings.relayUrl.first() }?.trim()?.takeIf { it.isNotBlank() }
        val relayPin = runBlocking { settings.relayPin.first() }?.trim()?.takeIf { it.isNotBlank() }

        if (relayUrl == null || relayPin == null) {
            val serviceName = if (isAllDebrid) "AllDebrid" else "Real-Debrid"
            throw IllegalStateException(
                tr(
                    "Configuración de Debrid Relay para $serviceName incompleta (falta URL o PIN)",
                    "Debrid Relay configuration for $serviceName incomplete (missing URL or PIN)",
                ),
            )
        }

        val parsedRelay = relayUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid relay URL: $relayUrl")

        val prefix = if (isAllDebrid) "alldebrid" else "realdebrid"
        val basePath = parsedRelay.encodedPath.trimEnd('/')

        val newUrl = url.newBuilder()
            .scheme(parsedRelay.scheme)
            .host(parsedRelay.host)
            .apply {
                if (parsedRelay.port != -1) port(parsedRelay.port)
                encodedPath("$basePath/$prefix${url.encodedPath}")
            }
            .build()

        val newRequest = request.newBuilder()
            .url(newUrl)
            .header("X-Relay-Token", relayPin)
            .build()

        return chain.proceed(newRequest)
    }
}
