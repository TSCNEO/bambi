package com.shieldstream.tv.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldstream.tv.domain.CustomCatalogRepository
import com.shieldstream.tv.model.tr
import com.shieldstream.tv.settings.profiles.PinDialog
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.onBackKey
import kotlinx.coroutines.launch

private val ForkMuted = Color.White.copy(alpha = 0.6f)
private val ForkGreen = Color(0xFF46D369)
private val ForkAmber = Color(0xFFF5B83D)
private val ForkRed = Color(0xFFFF5A5F)

internal enum class RelayField { URL, PIN }

@Composable
internal fun AutoFrameRateCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
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
                    color = ForkMuted,
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
internal fun SoftwareAudioCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
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
                    color = ForkMuted,
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
internal fun ExternalPlayerCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
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
                    color = ForkMuted,
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
internal fun ForkAboutCard(
    onPair: suspend (String) -> Boolean,
    isActivated: Boolean,
    onSync: suspend () -> CustomCatalogRepository.SyncResult,
    getCatalogVersion: () -> String?,
    upstreamVersion: String,
    onAboutClick: () -> Unit = {},
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
            onAboutClick()
            if (activatedState) {
                if (!isSyncing) {
                    isSyncing = true
                    coroutineScope.launch {
                        when (val res = onSync()) {
                            is CustomCatalogRepository.SyncResult.Updated -> {
                                catalogVersion = res.version
                                Toast.makeText(context, tr("Catálogo actualizado a v${res.version}", "Catalog updated to v${res.version}"), Toast.LENGTH_SHORT).show()
                            }
                            is CustomCatalogRepository.SyncResult.UpToDate -> {
                                catalogVersion = res.version
                                Toast.makeText(context, tr("Catálogo al día (v${res.version})", "Catalog up to date (v${res.version})"), Toast.LENGTH_SHORT).show()
                            }
                            is CustomCatalogRepository.SyncResult.Error -> {
                                Toast.makeText(context, tr("Error de sincronización: ${res.message}", "Sync error: ${res.message}"), Toast.LENGTH_SHORT).show()
                            }
                        }
                        isSyncing = false
                    }
                }
            } else {
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
        },
    ) { _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (testing) localizedStringResource(StringsR.string.settings_bambi_testing) else "Bambi",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    if (activatedState) {
                        val vText = catalogVersion?.let { " · v$it" } ?: ""
                        if (isSyncing) tr("Comprobando actualizaciones de catálogo...", "Checking catalog updates...")
                        else tr("Catálogo conectado$vText · fuentes directas activas (pulsa para buscar updates)", "Connected catalog$vText · direct streams active (click to sync)")
                    } else {
                        localizedStringResource(StringsR.string.settings_catalog_by_tmdb_sources_from_torrentio_playback)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activatedState) Color(0xFF4CAF50) else ForkMuted,
                )
            }
            val upstreamText = if (upstreamVersion.isNotBlank()) " · base $upstreamVersion" else ""
            Text(
                tr("Versión $version$upstreamText", "Version $version$upstreamText"),
                style = MaterialTheme.typography.titleMedium,
                color = ForkMuted,
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
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
                        Toast.makeText(context, tr("Fuentes sincronizadas", "Sources synchronized"), Toast.LENGTH_SHORT).show()
                    } else {
                        pinError = tr("PIN incorrecto o no disponible", "Invalid or unavailable PIN")
                    }
                }
            },
            onDismiss = {
                showPinDialog = false
                pinError = null
            },
        )
    }
}

/**
 * Debrid Relay: manda las peticiones de AllDebrid y Real-Debrid a un relay propio (VPS) con un PIN de 6 cifras,
 * para que la IP del relay sea la que ven los servicios. Cada fila es enfocable: OK edita, ◀ ▶ activan o
 * desactivan los interruptores. Las ventanas de edición las pinta la pantalla, no esta tarjeta.
 */
@Composable
internal fun RelaySettingsCard(
    url: String?,
    pin: String?,
    allDebrid: Boolean,
    realDebrid: Boolean,
    isVip: Boolean = false,
    onEdit: (RelayField) -> Unit,
    onAllDebrid: (Boolean) -> Unit,
    onRealDebrid: (Boolean) -> Unit,
    urlModifier: Modifier = Modifier,
    pinModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isVip) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF261E10), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFFFD479).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("👑", fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        tr("Servidor VIP Conectado", "VIP Server Connected"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFFFD479),
                    )
                    Text(
                        tr("Tu cuenta Debrid y Catálogo se configuran automáticamente desde tu servidor.", "Your Debrid and Catalog account are automatically configured from your server."),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
        FocusCard(modifier = urlModifier, onClick = { onEdit(RelayField.URL) }) { focused ->
            RelayRow(
                title = tr("URL del relay", "Relay URL"),
                value = url?.takeIf { it.isNotBlank() } ?: tr("No configurada", "Not set"),
                valueColor = if (url.isNullOrBlank()) ForkAmber else Color.White.copy(alpha = 0.8f),
                hint = tr("OK para cambiar ›", "OK to change ›"),
                focused = focused,
            )
        }
        FocusCard(modifier = pinModifier, onClick = { onEdit(RelayField.PIN) }) { focused ->
            RelayRow(
                title = tr("PIN del relay", "Relay PIN"),
                value = pin?.takeIf { it.isNotBlank() }?.let { "●".repeat(6) } ?: tr("No configurado", "Not set"),
                valueColor = if (pin.isNullOrBlank()) ForkAmber else Color.White.copy(alpha = 0.8f),
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
            color = if ((allDebrid || realDebrid) && (url.isNullOrBlank() || pin.isNullOrBlank())) ForkAmber else ForkMuted,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

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
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ForkMuted)
            }
            Spacer(Modifier.width(16.dp))
            RelaySwitch(checked = enabled)
        }
    }
}

@Composable
private fun RelaySwitch(checked: Boolean) {
    Box(
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .width(64.dp)
            .height(32.dp)
            .background(
                if (checked) ForkGreen.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(50),
            )
            .padding(3.dp),
    ) {
        Box(Modifier.size(26.dp).background(Color.White, CircleShape))
    }
}

@Composable
internal fun RelayEditorDialog(
    field: RelayField,
    initial: String,
    hasCurrentPin: Boolean = false,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
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
        val normalized = if (isPin || clean.isEmpty()) clean else if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"
        val host = if (isPin) null else runCatching { java.net.URI(normalized).host }.getOrNull()
        val problem = when {
            clean.isEmpty() && !isPin -> tr("Escribe la URL del relay", "Type the relay URL")
            clean.isEmpty() -> tr("Escribe el PIN", "Type the PIN")
            !isPin && host.isNullOrBlank() -> tr("La URL no es válida (revisa el dominio)", "The URL isn't valid (check the domain)")
            else -> null
        }
        if (problem != null) {
            error = problem
            return
        }
        onSave(if (isPin) clean else normalized.trimEnd('/'))
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
                            "PIN que el relay espera en la cabecera X-Relay-Token. Ya hay un PIN guardado: el nuevo lo sustituye.",
                            "PIN the relay expects in the X-Relay-Token header. A PIN is already saved: the new one replaces it.",
                        )
                    } else {
                        tr("PIN que el relay espera en la cabecera X-Relay-Token.", "PIN the relay expects in the X-Relay-Token header.")
                    }
                } else {
                    tr(
                        "Dirección del relay, p. ej. relay.midominio.com. Las peticiones van a /alldebrid y /realdebrid.",
                        "Relay address, e.g. relay.mydomain.com. Requests go to /alldebrid and /realdebrid.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ForkMuted,
            )
            Spacer(Modifier.height(20.dp))
            BasicTextField(
                value = input,
                onValueChange = {
                    val next = it.replace("\n", "")
                    input = if (isPin) next.filter { c -> c.isDigit() } else next
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
                                if (isPin) tr("PIN", "PIN") else "mi-servidor.com",
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
                    tr("Solo cifras", "Digits only")
                } else {
                    tr("Ej: mi-servidor.com", "e.g. my-server.com")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) ForkRed else ForkMuted,
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
