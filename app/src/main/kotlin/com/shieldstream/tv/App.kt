package com.shieldstream.tv

import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.AppLanguage
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.shieldstream.tv.data.security.TokenStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var tokenStore: TokenStore

    @Inject
    lateinit var customCatalog: com.shieldstream.tv.data.repository.CustomCatalogRepositoryImpl

    override fun onCreate() {
        // Idioma ANTES de super.onCreate(): ahí Hilt crea los singletons (repositorios, cliente de TMDB…) y
        // cualquier texto que preparen ya debe salir en el idioma elegido. Cambiarlo reinicia la app (restartApp).
        Lang.current = AppLanguage.fromName(
            getSharedPreferences(AppLanguage.PREFS, MODE_PRIVATE).getString(AppLanguage.PREF_KEY, null),
        ) ?: AppLanguage.default()
        super.onCreate()
        // Primera ejecución: las claves de local.properties (fuera de git) se guardan cifradas en el dispositivo.
        // Desde ahí mandan los Ajustes: si se cambian o se quitan allí, no vuelven a ponerse solas.
        tokenStore.seedOnce(TokenStore.RD_TOKEN, BuildConfig.RD_TOKEN)
        tokenStore.seedOnce(TokenStore.TMDB_KEY, BuildConfig.TMDB_API_KEY)
        tokenStore.seedOnce(TokenStore.SUBDL_KEY, BuildConfig.SUBDL_API_KEY)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            customCatalog.checkAndUpdate()
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader
}
