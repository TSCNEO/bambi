package com.shieldstream.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shieldstream.tv.domain.SelectedContentHolder
import com.shieldstream.tv.domain.TraktRepository
import com.shieldstream.tv.domain.UpdateRepository
import com.shieldstream.tv.ui.theme.ShieldStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var holder: SelectedContentHolder

    @Inject
    lateinit var trakt: TraktRepository

    @Inject
    lateinit var updates: UpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShieldStreamTheme {
                AppNavHost(holder = holder)
            }
        }
    }

    /** Al volver a la app (p. ej. tras ver algo en la otra Shield): se trae lo nuevo de Trakt (como mucho cada 2 min). */
    override fun onStart() {
        super.onStart()
        trakt.syncActive()
        // Versión nueva en GitHub: como mucho una consulta cada 6 h; el aviso sale en el Inicio / perfiles.
        updates.check(manual = false)
    }
}
