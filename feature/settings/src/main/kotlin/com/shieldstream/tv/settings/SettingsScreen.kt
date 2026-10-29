package com.shieldstream.tv.settings

import com.shieldstream.tv.domain.SourceProvider
import com.shieldstream.tv.ui.component.restartApp
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import com.shieldstream.tv.settings.update.UpdateCard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.ui.component.ProvideMinimalScroll
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.DisplayFont

private val Background = Brush.verticalGradient(listOf(Color(0xFF151622), Color.Black))
private val CardColor = Color(0xFF1A1C27)
private val CardFocused = Color(0xFF2A2D3D)
private val CardShape = RoundedCornerShape(14.dp)
private val Muted = Color.White.copy(alpha = 0.6f)
private val Green = Color(0xFF46D369)
private val Amber = Color(0xFFF5B83D)
private val Red = Color(0xFFFF5A5F)

/**
 * Ajustes: claves de los servicios (Real-Debrid, TMDB, SubDL), búfer del reproductor y versión.
 * Nota de foco: ningún elemento cambia de tamaño al enfocarse (sin escala) y las formas se dibujan en el
 * propio fondo, sin clip: en la Shield el clip se perdía y salían esquinas cuadradas.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = state as? SettingsUiState.Ready
    val context = LocalContext.current
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    // Campo del relay que se está editando (null = ninguna ventana abierta).
    // La ventana se pinta aquí, fuera de la lista: dentro de un item de LazyColumn no podría tapar la pantalla.
    var relayEditing by remember { mutableStateOf<RelayField?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            // Con la ventana de clave abierta, Atrás la cierra ella (va antes en la cadena de teclas).
            .onBackKey(enabled = ready?.editor == null && relayEditing == null) { onBack() },
    ) {
        when (val s = state) {
            SettingsUiState.Loading -> Unit
            is SettingsUiState.Ready -> {
                SettingsList(
                    state = s,
                    onEditKey = viewModel::openEditor,
                    onBuffer = viewModel::changeBuffer,
                    onSeekMode = viewModel::setSeekThumbnails,
                    onSoftwareAudio = viewModel::setForceSoftwareAudio,
                    onExternalPlayer = viewModel::setUseExternalPlayer,
                    onAutoFrameRate = viewModel::setAutoFrameRate,
                    onDebrid = viewModel::setDebrid,
                    relayEditing = relayEditing,
                    onRelayEdit = { relayEditing = it },
                    onRelayAllDebrid = viewModel::setEnableRelayAllDebrid,
                    onRelayRealDebrid = viewModel::setEnableRelayRealDebrid,
                    onOpenSetup = onOpenSetup,
                    onLanguage = { language -> viewModel.setLanguage(language) { restartApp(context) } },
                    providers = providers,
                    onToggleProvider = viewModel::toggleProvider,
                    onPairCustomCatalog = viewModel::pairCustomCatalog,
                    isCustomCatalogActivated = viewModel.isCustomCatalogActivated,
                    onSyncCustomCatalog = viewModel::syncCustomCatalog,
                    getCatalogVersion = viewModel::getCatalogVersion,
                    upstreamVersion = viewModel.upstreamVersion,
                )
                s.editor?.let { editor ->
                    KeyEditorDialog(
                        editor = editor,
                        onSave = viewModel::saveKey,
                        onRemove = viewModel::removeKey,
                        onClose = viewModel::closeEditor,
                    )
                }
                // Ventana del relay: se pinta sobre la lista, nunca dentro de un item (no taparía la pantalla).
                // El PIN no se rellena nunca: un secreto guardado no se vuelve a poner en pantalla ni en el estado guardado.
                relayEditing?.let { field ->
                    RelayEditorDialog(
                        field = field,
                        initial = if (field == RelayField.URL) s.relayUrl.orEmpty() else "",
                        hasCurrentPin = !s.relayPin.isNullOrBlank(),
                        onSave = { value ->
                            if (field == RelayField.URL) viewModel.setRelayUrl(value) else viewModel.setRelayPin(value)
                            relayEditing = null
                        },
                        onClose = { relayEditing = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsList(
    state: SettingsUiState.Ready,
    onEditKey: (ApiService) -> Unit,
    onBuffer: (Int) -> Unit,
    onSeekMode: (Boolean) -> Unit,
    onSoftwareAudio: (Boolean) -> Unit,
    onExternalPlayer: (Boolean) -> Unit,
    onAutoFrameRate: (Boolean) -> Unit,
    onDebrid: (DebridService) -> Unit,
    onRelayEdit: (RelayField?) -> Unit,
    onRelayAllDebrid: (Boolean) -> Unit,
    onRelayRealDebrid: (Boolean) -> Unit,
    relayEditing: RelayField?,
    onOpenSetup: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    providers: Set<SourceProvider>,
    onToggleProvider: (SourceProvider) -> Unit,
    onPairCustomCatalog: suspend (String) -> Boolean,
    isCustomCatalogActivated: Boolean,
    onSyncCustomCatalog: suspend () -> com.shieldstream.tv.domain.CustomCatalogRepository.SyncResult,
    getCatalogVersion: () -> String?,
    upstreamVersion: String,
) {
    val requesters = remember { ApiService.entries.associateWith { FocusRequester() } }
    val debridRequester = remember { FocusRequester() }
    val relayUrlRequester = remember { FocusRequester() }
    val relayPinRequester = remember { FocusRequester() }
    // El foco vuelve a la tarjeta del servicio que se estaba editando (al entrar: el selector de debrid).
    var lastEdited by rememberSaveable { mutableStateOf<ApiService?>(null) }
    // Última fila del relay editada, para devolverle el foco al cerrar su ventana.
    var lastRelayField by rememberSaveable { mutableStateOf<RelayField?>(null) }
    LaunchedEffect(state.editor == null) {
        if (state.editor == null) {
            withFrameNanos { }
            runCatching { (lastEdited?.let { requesters.getValue(it) } ?: debridRequester).requestFocus() }
        }
    }
    LaunchedEffect(relayEditing) {
        val field = lastRelayField
        if (relayEditing == null && field != null) {
            withFrameNanos { }
            runCatching {
                when (field) {
                    RelayField.URL -> relayUrlRequester
                    RelayField.PIN -> relayPinRequester
                }.requestFocus()
            }
        }
    }
    val debridUi = state.services.first { it.service == state.debrid.apiService }
    val otherServices = state.services.filter { !it.service.isDebrid }
    ProvideMinimalScroll(margin = 56.dp) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 120.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(tr("AJUSTES", "SETTINGS"), fontFamily = DisplayFont, fontSize = 52.sp, color = Color.White)
                    Text(
                        tr("Claves de los servicios, reproducción y versión de la app", "Service keys, playback and app version"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
            item { SectionTitle(tr("Servicio debrid", "Debrid service")) }
            item {
                DebridSelectorCard(
                    active = state.debrid,
                    onChange = onDebrid,
                    modifier = Modifier.focusRequester(debridRequester),
                )
            }
            item(key = "debrid_key") {
                ServiceCard(
                    ui = debridUi,
                    onClick = {
                        lastEdited = debridUi.service
                        onEditKey(debridUi.service)
                    },
                    modifier = Modifier.focusRequester(requesters.getValue(debridUi.service)),
                )
            }
            item {
                Text(
                    if (state.debrid.viaTorrentio) {
                        tr("${state.debrid.title} funciona a través de Torrentio, como en Stremio: tu clave se envía a ", "${state.debrid.title} works through Torrentio, like in Stremio: your key is sent to ") +
                            tr("Torrentio para que consulte la caché y pida los enlaces. Real-Debrid, en cambio, se conecta ", "Torrentio so it can check the cache and request the links. Real-Debrid, on the other hand, connects ") +
                            tr("directamente.", "directly.")
                    } else {
                        tr("Real-Debrid se conecta directamente desde la Shield: la clave no sale de tu casa.", "Real-Debrid connects directly from the Shield: your key never leaves your home.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            item { SectionTitle(tr("Debrid Relay", "Debrid Relay")) }
            item {
                RelaySettingsCard(
                    url = state.relayUrl,
                    pin = state.relayPin,
                    allDebrid = state.enableRelayAllDebrid,
                    realDebrid = state.enableRelayRealDebrid,
                    onEdit = { field ->
                        lastRelayField = field
                        onRelayEdit(field)
                    },
                    onAllDebrid = onRelayAllDebrid,
                    onRealDebrid = onRelayRealDebrid,
                    urlModifier = Modifier.focusRequester(relayUrlRequester),
                    pinModifier = Modifier.focusRequester(relayPinRequester),
                )
            }
            item { SectionTitle(tr("Otros servicios", "Other services")) }
            items(otherServices, key = { it.service.name }) { ui ->
                ServiceCard(
                    ui = ui,
                    onClick = {
                        lastEdited = ui.service
                        onEditKey(ui.service)
                    },
                    modifier = Modifier.focusRequester(requesters.getValue(ui.service)),
                )
            }
            item {
                Text(
                    tr("Torrentio (fuentes), OpenSubtitles y Podnapisi (subtítulos) no necesitan clave.", "Torrentio (sources), OpenSubtitles and Podnapisi (subtitles) don't need a key."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            item { SectionTitle(tr("Buscadores de fuentes", "Source finders")) }
            // Una tarjeta por sección (Torrentio, The Pirate Bay = todos los buscadores propios, AIOStreams).
            items(SourceProvider.entries.distinctBy { it.section }) { p ->
                ProviderCard(p, enabled = SourceProvider.entries.filter { it.section == p.section }.all { it in providers }, onClick = { onToggleProvider(p) })
            }
            item { SpanishFirstCard() }
            item { SectionTitle(tr("Inicio", "Home")) }
            item { HomeCategoriesCard() }
            item { SectionTitle("Idioma · Language") }
            item { LanguageCard(current = state.language, onApply = onLanguage) }
            item { SectionTitle(tr("Reproducción", "Playback")) }
            item { SeekModeCard(thumbnails = state.seekThumbnails, onChange = onSeekMode) }
            item { AutoFrameRateCard(enabled = state.autoFrameRate, onChange = onAutoFrameRate) }
            item { SoftwareAudioCard(enabled = state.forceSoftwareAudio, onChange = onSoftwareAudio) }
            item { ExternalPlayerCard(enabled = state.useExternalPlayer, onChange = onExternalPlayer) }
            item { AutoNextCard() }
            item { BufferCard(seconds = state.bufferSeconds, onChange = onBuffer) }
            item { SectionTitle(tr("Acerca de", "About")) }
            item {
                AboutCard(
                    onPair = onPairCustomCatalog,
                    isActivated = isCustomCatalogActivated,
                    onSync = onSyncCustomCatalog,
                    getCatalogVersion = getCatalogVersion,
                    upstreamVersion = upstreamVersion,
                )
            }
            item { UpdateCard() }
            item { SetupGuideCard(onClick = onOpenSetup) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = DisplayFont,
        fontSize = 26.sp,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp),
    )
}

/** Tarjeta enfocable común: fondo y borde con forma (sin clip ni escala). */
@Composable
private fun FocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .background(if (focused) CardFocused else CardColor, CardShape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = CardShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick ?: {})
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        content(focused)
    }
}

@Composable
private fun ServiceCard(ui: ServiceUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(modifier = modifier, onClick = onClick) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServiceLogo(ui.service)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.service.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            ui.service.isDebrid -> tr("En uso", "In use")
                            ui.service.required -> tr("Necesaria", "Required")
                            else -> tr("Opcional", "Optional")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                    )
                }
                Text(ui.service.usedFor, style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    ui.maskedKey ?: tr("Sin clave", "No key"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(ui.status)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ui.maskedKey == null) tr("OK para añadir ›", "OK to add ›") else tr("OK para cambiar ›", "OK to change ›"),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused) Color.White else Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun ServiceLogo(service: ApiService) {
    val (initials, color) = when (service) {
        ApiService.REAL_DEBRID -> "RD" to Color(0xFF6DB33F)
        ApiService.TORBOX -> "TB" to Color(0xFF7C5CFF)
        ApiService.PREMIUMIZE -> "PM" to Color(0xFFE5533D)
        ApiService.ALLDEBRID -> "AD" to Color(0xFFE9A21B)
        ApiService.DEBRID_LINK -> "DL" to Color(0xFF2F7DE1)
        ApiService.OFFCLOUD -> "OC" to Color(0xFF1FA58E)
        ApiService.TMDB -> "TM" to Color(0xFF01B4E4)
        ApiService.SUBDL -> "SD" to Color(0xFFF08A24)
        ApiService.AIOSTREAMS -> "+" to Color(0xFF8E5CF7)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.dp).background(color, CircleShape),
    ) {
        Text(initials, fontFamily = DisplayFont, fontSize = 24.sp, color = Color.White)
    }
}

@Composable
private fun StatusPill(status: ServiceStatus) {
    val (color, text) = when (status) {
        ServiceStatus.Missing -> Amber to tr("Sin configurar", "Not set up")
        ServiceStatus.Checking -> Muted to tr("Comprobando…", "Checking…")
        is ServiceStatus.Ok -> Green to (status.detail ?: tr("Conectado", "Connected"))
        is ServiceStatus.Bad -> Red to status.message
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}

/** Vuelve a abrir la guía de configuración inicial (paso a paso, con QR). */
@Composable
private fun SetupGuideCard(onClick: () -> Unit) {
    FocusCard(onClick = onClick) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Guía de configuración", "Setup guide"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr("Repite paso a paso la configuración del primer arranque (catálogo, debrid y subtítulos).", "Repeat the first-run setup step by step (catalog, debrid and subtitles)."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Text(
                tr("OK para abrir ›", "OK to open ›"),
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Color.White else Color.Transparent,
            )
        }
    }
}

/** Servicio debrid en uso: ◀ ▶ (u OK) recorren Real-Debrid, TorBox, Premiumize, AllDebrid, Debrid-Link y Offcloud. */
@Composable
internal fun DebridSelectorCard(active: DebridService, onChange: (DebridService) -> Unit, modifier: Modifier = Modifier) {
    val all = DebridService.entries
    fun shift(delta: Int) = onChange(all[(all.indexOf(active) + delta + all.size) % all.size])
    FocusCard(
        onClick = { shift(1) },
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { shift(-1); true }
                Key.DirectionRight -> { shift(1); true }
                else -> false
            }
        },
    ) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Servicio en uso", "Service in use"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    if (active.viaTorrentio) tr("Fuentes y enlaces a través de Torrentio.", "Sources and links through Torrentio.") else tr("Conexión directa con Real-Debrid.", "Direct connection to Real-Debrid."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Arrow("◀", enabled = focused)
                    Text(
                        active.title.uppercase(),
                        fontFamily = DisplayFont,
                        fontSize = 30.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    Arrow("▶", enabled = focused)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    all.forEach { service ->
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(
                                    if (service == active) Color.White else Color.White.copy(alpha = 0.25f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cómo funcionan ◀▶ en el reproductor. OK alterna; ◀ elige "Miniaturas" y ▶ "Saltos de 10 s".
 */
/**
 * "Castellano primero": en Fuentes y al pulsar Reproducir, las releases con audio en español van delante (tras las
 * que están en caché). Se detecta por la bandera de Torrentio o por el nombre (SPANISH, CASTELLANO, DUAL…).
 */
@Composable
private fun SpanishFirstCard(viewModel: SettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.spanishFirst.collectAsStateWithLifecycle()
    FocusCard(
        onClick = { viewModel.setSpanishFirst(!enabled) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { viewModel.setSpanishFirst(true); true }
                Key.DirectionRight -> { viewModel.setSpanishFirst(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Castellano primero", "Spanish audio first"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr(
                        "Las fuentes con audio en español van delante (también al pulsar Reproducir). Se reconocen por la bandera o por el nombre: SPANISH, CASTELLANO, DUAL…",
                        "Sources with Spanish audio go first (also when you press Play). Detected by flag or name: SPANISH, CASTELLANO, DUAL…",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                SegmentOption(tr("Sí", "On"), selected = enabled)
                SegmentOption(tr("No", "Off"), selected = !enabled)
            }
        }
    }
}

/** Series: "Siguiente episodio" en los créditos y paso automático al terminar. ◀ = Sí, ▶ = No, OK cambia. */
@Composable
private fun AutoNextCard(viewModel: SettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.autoNextEpisode.collectAsStateWithLifecycle()
    FocusCard(
        onClick = { viewModel.setAutoNextEpisode(!enabled) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { viewModel.setAutoNextEpisode(true); true }
                Key.DirectionRight -> { viewModel.setAutoNextEpisode(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Siguiente episodio automático", "Autoplay next episode"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr(
                        "Series: en los créditos sale \"Siguiente episodio\" (OK lo pone ya) y, al terminar el episodio, empieza el siguiente.",
                        "Shows: during the credits \"Next episode\" appears (OK plays it now) and, when the episode ends, the next one starts.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                SegmentOption(tr("Sí", "On"), selected = enabled)
                SegmentOption(tr("No", "Off"), selected = !enabled)
            }
        }
    }
}

@Composable
private fun AutoFrameRateCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FocusCard(
        onClick = { onChange(!enabled) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onChange(true); true }
                Key.DirectionRight -> { onChange(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Ajuste automático de frecuencia (AFR)", "Auto frame rate matching (AFR)"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr(
                        "Cambia la tasa de refresco del televisor (24p, 50p, 60p) según los fotogramas del vídeo para eliminar tirones (judder).",
                        "Switches TV refresh rate (24p, 50p, 60p) to match video frame rate and eliminate judder.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                SegmentOption(tr("Sí", "On"), selected = enabled)
                SegmentOption(tr("No", "Off"), selected = !enabled)
            }
        }
    }
}

@Composable
private fun SoftwareAudioCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FocusCard(
        onClick = { onChange(!enabled) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onChange(true); true }
                Key.DirectionRight -> { onChange(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Forzar audio por software (PCM)", "Force software audio (PCM)"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr(
                        "Decodifica E-AC-3, DTS y TrueHD en la CPU a PCM estéreo/multicanal. Soluciona el silencio en barras o TVs que no soportan bitstream HDMI.",
                        "Decodes E-AC-3, DTS and TrueHD on CPU to PCM stereo/multichannel. Fixes silence on soundbars or TVs without HDMI bitstream support.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                SegmentOption(tr("Sí", "On"), selected = enabled)
                SegmentOption(tr("No", "Off"), selected = !enabled)
            }
        }
    }
}

@Composable
private fun ExternalPlayerCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FocusCard(
        onClick = { onChange(!enabled) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onChange(true); true }
                Key.DirectionRight -> { onChange(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Reproductor externo por defecto", "Default external player"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr(
                        "Abre automáticamente el vídeo en Just Player, VLC u otro reproductor externo instalado al pulsar Reproducir.",
                        "Automatically opens the video in Just Player, VLC or another installed external player when you press Play.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                SegmentOption(tr("Sí", "On"), selected = enabled)
                SegmentOption(tr("No", "Off"), selected = !enabled)
            }
        }
    }
}
@Composable
private fun SeekModeCard(thumbnails: Boolean, onChange: (Boolean) -> Unit) {
    FocusCard(
        onClick = { onChange(!thumbnails) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onChange(true); true }
                Key.DirectionRight -> { onChange(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Búsqueda en el reproductor", "Seeking in the player"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    if (thumbnails) {
                        tr("◀ ▶ pausan y muestran miniaturas del punto elegido; OK salta ahí y Atrás vuelve.", "◀ ▶ pause and show thumbnails of the chosen point; OK jumps there and Back returns.")
                    } else {
                        tr("◀ ▶ adelantan o retroceden 10 s al momento, sin pausar.", "◀ ▶ skip forward or back 10 s instantly, without pausing.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                SegmentOption(tr("Miniaturas", "Thumbnails"), selected = thumbnails)
                SegmentOption(tr("Saltos de 10 s", "10 s skips"), selected = !thumbnails)
            }
        }
    }
}

/**
 * Idioma de la app. ◀ ▶ eligen y OK aplica: se guarda y la app se reinicia, así todos los textos (y TMDB, el
 * audio preferido, las edades…) salen ya en el idioma nuevo. El título va en los dos idiomas para que se encuentre
 * aunque no se entienda el actual. Si el foco sale de la tarjeta sin pulsar OK, no cambia nada.
 */
@Composable
private fun LanguageCard(current: AppLanguage, onApply: (AppLanguage) -> Unit) {
    var pending by remember(current) { mutableStateOf(current) }
    FocusCard(
        onClick = { if (pending != current) onApply(pending) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { pending = AppLanguage.ES; true }
                Key.DirectionRight -> { pending = AppLanguage.EN; true }
                else -> false
            }
        },
    ) { focused ->
        LaunchedEffect(focused) { if (!focused) pending = current }
        val changing = pending != current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Idioma de la app", "App language"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    if (changing) {
                        if (pending == AppLanguage.EN) "OK to switch to English: the app will restart." else "OK para cambiar a español: la app se reiniciará."
                    } else {
                        tr("Menús, títulos y sinopsis, y audio preferido. ◀ ▶ para elegir.", "Menus, titles and synopses, and preferred audio. ◀ ▶ to choose.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (changing) Amber else Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                AppLanguage.entries.forEach { SegmentOption(it.label, selected = it == pending) }
            }
        }
    }
}

/** Buscador de fuentes: OK lo activa o lo desactiva. Con varios activados, "Fuentes" sale por secciones. */
@Composable
private fun ProviderCard(provider: SourceProvider, enabled: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(provider.section, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
                Text(providerNote(provider), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Spacer(Modifier.width(24.dp))
            SegmentOption(if (enabled) tr("Activado", "On") else tr("Desactivado", "Off"), selected = enabled)
        }
    }
}

private fun providerNote(p: SourceProvider): String = when (p) {
    SourceProvider.TORRENTIO -> tr("El buscador principal, como en Stremio.", "The main finder, like in Stremio.")
    SourceProvider.PEERFLIX -> tr("Torrents en castellano de webs españolas (DonTorrent, MejorTorrent, Wolfmax4k…) y algunos en inglés.", "Spanish torrents from Spanish sites (DonTorrent, MejorTorrent, Wolfmax4k…) plus some in English.")
    SourceProvider.KNABEN,
    SourceProvider.THEPIRATEBAY -> tr("Buscadores propios de Bambi: The Pirate Bay, Knaben y Nyaa (Real-Debrid y AllDebrid).", "Bambi's own finders: The Pirate Bay, Knaben and Nyaa (Real-Debrid and AllDebrid).")
    SourceProvider.NYAA -> tr("Buscador propio de anime (CocoScrapers).", "Own anime finder (CocoScrapers).")
    SourceProvider.COMET -> tr("Comet con tu token de Real-Debrid (se envía a comet.elfhosted.com, como en Umbrella).", "Comet with your Real-Debrid token (sent to comet.elfhosted.com, like Umbrella).")
    SourceProvider.AIOSTREAMS -> tr("Cualquier addon de Stremio (AIOStreams, Comet, MediaFusion…). Pega la URL de su manifiesto en Otros servicios → Addon de Stremio.", "Any Stremio addon (AIOStreams, Comet, MediaFusion…). Paste its manifest URL in Other services → Stremio addon.")
}

@Composable
private fun SegmentOption(text: String, selected: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.7f),
        modifier = Modifier
            .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** ◀ ▶ cambian el búfer de 10 en 10 (50–100 s) sin salir de la tarjeta. */
@Composable
private fun BufferCard(seconds: Int, onChange: (Int) -> Unit) {
    FocusCard(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> {
                    if (seconds > MIN_BUFFER) onChange(-10)
                    true
                }
                Key.DirectionRight -> {
                    if (seconds < MAX_BUFFER) onChange(10)
                    true
                }
                else -> false
            }
        },
    ) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Búfer del reproductor", "Player buffer"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    tr("Segundos de vídeo que se cargan por adelantado. Más búfer aguanta mejor los cortes de conexión.", "Seconds of video loaded ahead. A bigger buffer copes better with connection drops."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Arrow("◀", enabled = focused && seconds > MIN_BUFFER)
                    Text(
                        "$seconds s",
                        fontFamily = DisplayFont,
                        fontSize = 34.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    Arrow("▶", enabled = focused && seconds < MAX_BUFFER)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (MIN_BUFFER..MAX_BUFFER step 10).forEach { step ->
                        Box(
                            Modifier
                                .width(22.dp)
                                .height(4.dp)
                                .background(
                                    if (step <= seconds) Color.White else Color.White.copy(alpha = 0.18f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Arrow(symbol: String, enabled: Boolean) {
    Text(symbol, fontSize = 18.sp, color = if (enabled) Color.White else Color.White.copy(alpha = 0.2f))
}

@Composable
private fun AboutCard(
    onPair: suspend (String) -> Boolean,
    isActivated: Boolean,
    onSync: suspend () -> com.shieldstream.tv.domain.CustomCatalogRepository.SyncResult,
    getCatalogVersion: () -> String?,
    upstreamVersion: String,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var activatedState by remember { mutableStateOf(isActivated) }
    var catalogVersion by remember { mutableStateOf(getCatalogVersion()) }
    var isSyncing by remember { mutableStateOf(false) }

    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val testing = context.packageName.endsWith(".debug")
    FocusCard(
        onClick = {
            if (activatedState) {
                // Si ya está activado: un clic comprueba/fuerza actualización de catálogo
                if (!isSyncing) {
                    isSyncing = true
                    coroutineScope.launch {
                        when (val res = onSync()) {
                            is com.shieldstream.tv.domain.CustomCatalogRepository.SyncResult.Updated -> {
                                catalogVersion = res.version
                                android.widget.Toast.makeText(context, tr("Catálogo actualizado a v${res.version}", "Catalog updated to v${res.version}"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            is com.shieldstream.tv.domain.CustomCatalogRepository.SyncResult.UpToDate -> {
                                catalogVersion = res.version
                                android.widget.Toast.makeText(context, tr("Catálogo al día (v${res.version})", "Catalog up to date (v${res.version})"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            is com.shieldstream.tv.domain.CustomCatalogRepository.SyncResult.Error -> {
                                android.widget.Toast.makeText(context, tr("Error de sincronización: ${res.message}", "Sync error: ${res.message}"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        isSyncing = false
                    }
                }
            } else {
                // Si no está activado: 5 clics para el PIN
                val now = System.currentTimeMillis()
                if (now - lastClickTime < 1500L) {
                    clickCount++
                } else {
                    clickCount = 1
                }
                lastClickTime = now
                if (clickCount >= 5) {
                    clickCount = 0
                    showPinDialog = true
                }
            }
        }
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (testing) tr("Bambi (pruebas)", "Bambi (testing)") else "Bambi",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    if (activatedState) {
                        val vText = catalogVersion?.let { " · v$it" } ?: ""
                        if (isSyncing) tr("Comprobando actualizaciones de catálogo...", "Checking catalog updates...")
                        else tr("Catálogo conectado$vText · fuentes directas activas (pulsa para buscar updates)", "Connected catalog$vText · direct streams active (click to sync)")
                    } else {
                        tr("Catálogo de TMDB · fuentes de Torrentio · reproducción con Real-Debrid", "Catalog by TMDB · sources from Torrentio · playback via Real-Debrid")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activatedState) Color(0xFF4CAF50) else Muted,
                )
            }
            val upstreamText = if (upstreamVersion.isNotBlank()) " · base $upstreamVersion" else ""
            Text(
                tr("Versión $version$upstreamText", "Version $version$upstreamText"),
                style = MaterialTheme.typography.titleMedium,
                color = Muted,
            )
        }
    }

    if (showPinDialog) {
        com.shieldstream.tv.settings.profiles.PinDialog(
            title = tr("Índice de fuentes", "Source Index"),
            subtitle = tr("Introduce el PIN de sincronización", "Enter sync PIN"),
            error = pinError,
            length = 6,
            onPin = { pin ->
                coroutineScope.launch {
                    val ok = onPair(pin)
                    if (ok) {
                        activatedState = true
                        catalogVersion = getCatalogVersion()
                        showPinDialog = false
                        pinError = null
                        android.widget.Toast.makeText(context, tr("Fuentes sincronizadas", "Sources synchronized"), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        pinError = tr("PIN incorrecto o no disponible", "Invalid or unavailable PIN")
                    }
                }
            },
            onDismiss = {
                showPinDialog = false
                pinError = null
            }
        )
    }
}

/**
 * Debrid Relay: manda las peticiones de AllDebrid y Real-Debrid a un relay propio (VPS) con un PIN de 6 cifras,
 * para que la IP del relay sea la que ven los servicios. Cada fila es enfocable: OK edita, ◀ ▶ activan o
 * desactivan los interruptores. Las ventanas de edición las pinta la pantalla, no esta tarjeta.
 */
@Composable
private fun RelaySettingsCard(
    url: String?,
    pin: String?,
    allDebrid: Boolean,
    realDebrid: Boolean,
    onEdit: (RelayField) -> Unit,
    onAllDebrid: (Boolean) -> Unit,
    onRealDebrid: (Boolean) -> Unit,
    urlModifier: Modifier = Modifier,
    pinModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(modifier = urlModifier, onClick = { onEdit(RelayField.URL) }) { focused ->
            RelayRow(
                title = tr("URL del relay", "Relay URL"),
                value = url?.takeIf { it.isNotBlank() } ?: tr("No configurada", "Not set"),
                valueColor = if (url.isNullOrBlank()) Amber else Color.White.copy(alpha = 0.8f),
                hint = tr("OK para cambiar ›", "OK to change ›"),
                focused = focused,
            )
        }
        FocusCard(modifier = pinModifier, onClick = { onEdit(RelayField.PIN) }) { focused ->
            RelayRow(
                title = tr("PIN del relay", "Relay PIN"),
                value = pin?.takeIf { it.isNotBlank() }?.let { "●".repeat(6) } ?: tr("No configurado", "Not set"),
                valueColor = if (pin.isNullOrBlank()) Amber else Color.White.copy(alpha = 0.8f),
                hint = tr("OK para cambiar ›", "OK to change ›"),
                focused = focused,
            )
        }
        RelayToggleRow(
            title = tr("AllDebrid por el relay", "AllDebrid through the relay"),
            subtitle = tr(
                "Las peticiones a AllDebrid salen desde la IP del relay (necesita URL y PIN).",
                "AllDebrid requests go out from the relay's IP (needs URL and PIN).",
            ),
            enabled = allDebrid,
            onToggle = onAllDebrid,
        )
        RelayToggleRow(
            title = tr("Real-Debrid por el relay", "Real-Debrid through the relay"),
            subtitle = tr(
                "Las peticiones a Real-Debrid salen desde la IP del relay (necesita URL y PIN).",
                "Real-Debrid requests go out from the relay's IP (needs URL and PIN).",
            ),
            enabled = realDebrid,
            onToggle = onRealDebrid,
        )
        Text(
            if (allDebrid || realDebrid) {
                if (url.isNullOrBlank() || pin.isNullOrBlank()) {
                    tr(
                        "Relay activado pero sin URL o PIN: el debrid fallará hasta que lo configures.",
                        "Relay enabled without URL or PIN: debrid will fail until you set them.",
                    )
                } else {
                    tr(
                        "Relay activo. Si falla, la app usará la conexión directa al desactivarlo.",
                        "Relay active. If it fails, turn it off to go back to a direct connection.",
                    )
                }
            } else {
                tr(
                    "Desactivado: AllDebrid y Real-Debrid se conectan directamente desde la Shield.",
                    "Off: AllDebrid and Real-Debrid connect directly from the Shield.",
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if ((allDebrid || realDebrid) && (url.isNullOrBlank() || pin.isNullOrBlank())) Amber else Muted,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

private enum class RelayField { URL, PIN }

@Composable
private fun RelayRow(title: String, value: String, valueColor: Color, hint: String, focused: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            hint,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Color.White else Color.Transparent,
        )
    }
}

/** Interruptor de una tarjeta: OK lo cambia y ◀ ▶ también (como el resto de ajustes). */
@Composable
private fun RelayToggleRow(title: String, subtitle: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    FocusCard(
        onClick = { onToggle(!enabled) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onToggle(true); true }
                Key.DirectionRight -> { onToggle(false); true }
                else -> false
            }
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Spacer(Modifier.width(16.dp))
            RelaySwitch(checked = enabled)
        }
    }
}

/** Interruptor dibujado a mano: Material no tiene Switch con el tamaño que pide la tele. */
@Composable
private fun RelaySwitch(checked: Boolean) {
    Box(
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .width(64.dp)
            .height(32.dp)
            .background(
                if (checked) Green.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(50),
            )
            .padding(3.dp),
    ) {
        Box(Modifier.size(26.dp).background(Color.White, CircleShape))
    }
}

/** Ventana para escribir la URL del relay o el PIN. Valida antes de guardar. */
@Composable
private fun RelayEditorDialog(
    field: RelayField,
    initial: String,
    hasCurrentPin: Boolean = false,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    // La URL no es un secreto: se conserva al recomponer. El PIN sí lo es, así que no se guarda en el estado guardado.
    var input by rememberSaveable(field) { mutableStateOf(if (field == RelayField.PIN) "" else initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(field) {
        withFrameNanos { }
        runCatching { fieldRequester.requestFocus() }
    }
    val isPin = field == RelayField.PIN

    fun submit() {
        val clean = input.trim()
        // Misma validación que aplicará el interceptor: si aquí no hay host, en reproducción tampoco.
        val host = if (isPin) null else runCatching { java.net.URI(clean).host }.getOrNull()
        val problem = when {
            clean.isEmpty() && !isPin -> tr(
                "Escribe la URL del relay (http:// o https://)",
                "Type the relay URL (http:// or https://)",
            )
            clean.isEmpty() -> tr("Escribe el PIN de 6 cifras", "Type the 6-digit PIN")
            isPin && (clean.length != 6 || clean.any { !it.isDigit() }) ->
                tr("El PIN debe tener 6 cifras", "The PIN must have 6 digits")
            !isPin && !(clean.startsWith("http://") || clean.startsWith("https://")) ->
                tr("La URL debe empezar por http:// o https://", "The URL must start with http:// or https://")
            !isPin && host.isNullOrBlank() ->
                tr("La URL no es válida (revisa el dominio)", "The URL isn't valid (check the domain)")
            else -> null
        }
        if (problem != null) {
            error = problem
            return
        }
        // Se guarda sin barras finales: las rutas se montan como "<url>/alldebrid" y "<url>/stream".
        onSave(if (isPin) clean else clean.trimEnd('/'))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .onBackKey(enabled = true) { onClose() },
    ) {
        Column(
            Modifier
                .width(720.dp)
                .background(Color(0xFF1B1D29), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                .padding(36.dp),
        ) {
            Text(
                if (isPin) tr("PIN del Debrid Relay", "Debrid Relay PIN") else tr("URL del Debrid Relay", "Debrid Relay URL"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(
                if (isPin) {
                    if (hasCurrentPin) {
                        tr(
                            "6 cifras que el relay espera en la cabecera X-Relay-Token. Ya hay un PIN guardado: el nuevo lo sustituye.",
                            "6 digits the relay expects in the X-Relay-Token header. A PIN is already saved: the new one replaces it.",
                        )
                    } else {
                        tr("6 cifras que el relay espera en la cabecera X-Relay-Token.", "6 digits the relay expects in the X-Relay-Token header.")
                    }
                } else {
                    tr(
                        "Dirección del relay, p. ej. https://relay.midominio.com. Las peticiones van a /alldebrid y /realdebrid.",
                        "Relay address, e.g. https://relay.mydomain.com. Requests go to /alldebrid and /realdebrid.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Spacer(Modifier.height(20.dp))
            BasicTextField(
                value = input,
                onValueChange = {
                    val next = it.replace("\n", "")
                    input = if (isPin) next.filter { c -> c.isDigit() }.take(6) else next
                    error = null
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    submit()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldRequester)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                if (event.type == KeyEventType.KeyUp) keyboard?.show()
                                true
                            }
                            Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) runCatching { saveRequester.requestFocus() }
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF101119), RoundedCornerShape(10.dp))
                            .border(
                                width = if (fieldFocused) 2.dp else 1.dp,
                                color = if (fieldFocused) Color.White else Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                if (isPin) tr("6 cifras", "6 digits") else "https://",
                                fontSize = 18.sp,
                                color = Color.White.copy(alpha = 0.35f),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                error ?: if (isPin) {
                    tr("Solo cifras, ${input.length}/6", "Digits only, ${input.length}/6")
                } else {
                    tr("Debe empezar por http:// o https://", "Must start with http:// or https://")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) Red else Muted,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = tr("Guardar", "Save"),
                    onClick = { submit() },
                    modifier = Modifier.focusRequester(saveRequester),
                )
                TvButton(text = tr("Cancelar", "Cancel"), onClick = onClose)
            }
        }
    }
}

/**
 * Ventana para escribir una clave. Se comprueba contra el servicio antes de guardarla.
 * En la tele: OK sobre el campo abre el teclado del sistema.
 */
@Composable
private fun KeyEditorDialog(
    editor: KeyEditor,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    var input by rememberSaveable(editor.service) { mutableStateOf("") }
    val fieldRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editor.service) {
        withFrameNanos { }
        runCatching { fieldRequester.requestFocus() }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .onBackKey(enabled = true) { onClose() },
    ) {
        Column(
            Modifier
                .width(720.dp)
                .background(Color(0xFF1B1D29), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                .padding(36.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(editor.service)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        editor.service.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Text(
                        tr("${editor.service.keyName} · consíguela en ${editor.service.whereToGet}", "${editor.service.keyName} · get it at ${editor.service.whereToGet}"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it.replace("\n", "") },
                singleLine = true,
                enabled = !editor.busy,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onSave(input)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldRequester)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    // OK del mando sobre el campo → teclado en pantalla.
                    // ▼ → botones: el campo de texto se queda con las flechas y no dejaba salir hacia abajo.
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                if (event.type == KeyEventType.KeyUp) keyboard?.show()
                                true
                            }
                            Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) runCatching { saveRequester.requestFocus() }
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { field ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF101119), RoundedCornerShape(10.dp))
                            .border(
                                width = if (fieldFocused) 2.dp else 1.dp,
                                color = if (fieldFocused) Color.White else Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                if (fieldFocused) tr("Pulsa OK para escribir la clave nueva", "Press OK to type the new key") else tr("Clave nueva", "New key"),
                                fontSize = 18.sp,
                                color = Color.White.copy(alpha = 0.35f),
                            )
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    editor.busy -> tr("Comprobando con ${editor.service.title}…", "Checking with ${editor.service.title}…")
                    editor.error != null -> editor.error
                    else -> tr("Se comprueba con ${editor.service.title} antes de guardarla.", "It's checked with ${editor.service.title} before saving.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    editor.busy -> Muted
                    editor.error != null -> Red
                    else -> Muted
                },
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = tr("Comprobar y guardar", "Check and save"),
                    onClick = { onSave(input) },
                    enabled = !editor.busy,
                    modifier = Modifier.focusRequester(saveRequester),
                )
                if (!editor.service.required && editor.hasKey) {
                    TvButton(text = tr("Quitar clave", "Remove key"), onClick = onRemove, enabled = !editor.busy)
                }
                TvButton(text = tr("Cancelar", "Cancel"), onClick = onClose, enabled = !editor.busy)
            }
        }
    }
}

private const val MIN_BUFFER = 50
private const val MAX_BUFFER = 100
