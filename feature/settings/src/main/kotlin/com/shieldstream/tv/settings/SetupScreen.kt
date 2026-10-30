package com.shieldstream.tv.settings

import androidx.compose.ui.platform.LocalContext
import com.shieldstream.tv.ui.component.restartApp
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.DebridService
import com.shieldstream.tv.ui.R
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.theme.DisplayFont

private val Background = Brush.verticalGradient(listOf(Color(0xFF151622), Color.Black))
private val Panel = Color(0xFF1A1C27)
private val Muted = Color.White.copy(alpha = 0.62f)
private val Green = Color(0xFF46D369)
private val Red = Color(0xFFFF5A5F)
private val Accent = Color(0xFFE50914)

/**
 * Guía de configuración (primer arranque sin claves, o desde Ajustes → "Guía de configuración").
 * Bienvenida → 1 Catálogo (TMDB) → 2 Servicio debrid → 3 Subtítulos (SubDL, opcional) → Todo listo.
 * Cada paso explica cómo sacar la clave y enseña un QR que abre esa página en el móvil.
 *
 * [onExit]: Atrás en la bienvenida o "Más tarde". [onFinish]: "Empezar a ver".
 */
@Composable
fun SetupScreen(
    onFinish: () -> Unit,
    onExit: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showServerDialog by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .onBackKey(enabled = true) {
                if (showServerDialog) {
                    showServerDialog = false
                    viewModel.clearError()
                } else if (!viewModel.back()) {
                    onExit()
                }
            },
    ) {
        val s = state as? SetupUiState.Ready ?: return@Box
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp)) {
            SetupHeader(s.step)
            Spacer(Modifier.height(14.dp))
            when (s.step) {
                SetupStep.WELCOME -> WelcomeStep(
                    onStart = viewModel::next,
                    onServer = { showServerDialog = true },
                    onLater = onExit,
                    onLanguage = {
                        val other = if (Lang.isEnglish) AppLanguage.ES else AppLanguage.EN
                        viewModel.setLanguage(other) { restartApp(context) }
                    },
                )
                SetupStep.TMDB -> KeyStep(
                    state = s,
                    service = ApiService.TMDB,
                    title = tr("Catálogo", "Catalog"),
                    intro = tr("Bambi saca de TMDB las portadas, sinopsis, temporadas y edades recomendadas. La clave es gratis.", "Bambi gets artwork, synopses, seasons and age ratings from TMDB. The key is free."),
                    steps = listOf(
                        tr("Crea una cuenta gratis en themoviedb.org (o entra con la tuya).", "Create a free account at themoviedb.org (or sign in)."),
                        tr("En tu perfil: Ajustes → API → solicita una clave de uso personal.", "In your profile: Settings → API → request a key for personal use."),
                        tr("Copia la «Clave de la API» (32 letras y números, no el token largo) y escríbela aquí.", "Copy the “API Key” (32 letters and numbers, not the long token) and type it here."),
                    ),
                    required = true,
                    onSubmit = { viewModel.submitKey(ApiService.TMDB, it) },
                    onNext = viewModel::next,
                    onTyping = viewModel::clearError,
                )
                SetupStep.DEBRID -> KeyStep(
                    state = s,
                    service = s.debrid.apiService,
                    title = tr("Servicio debrid", "Debrid service"),
                    intro = tr("El debrid es el que reproduce las películas a toda velocidad. Elige el tuyo con ◀ ▶ y pon su clave.", "The debrid service is what plays movies at full speed. Choose yours with ◀ ▶ and enter its key.") +
                        if (s.debrid.viaTorrentio) tr(" Va a través de Torrentio, como en Stremio.", " It works through Torrentio, like in Stremio.") else "",
                    steps = debridSteps(s.debrid),
                    required = true,
                    onSubmit = { viewModel.submitKey(s.debrid.apiService, it) },
                    onNext = viewModel::next,
                    onTyping = viewModel::clearError,
                    selector = { modifier -> DebridSelectorCard(active = s.debrid, onChange = viewModel::setDebrid, modifier = modifier) },
                )
                SetupStep.SUBDL -> KeyStep(
                    state = s,
                    service = ApiService.SUBDL,
                    title = tr("Subtítulos extra", "Extra subtitles"),
                    intro = tr("Opcional. Con una clave de SubDL aparecen más subtítulos externos. OpenSubtitles y Podnapisi ", "Optional. With a SubDL key you get more external subtitles. OpenSubtitles and Podnapisi ") +
                        tr("ya funcionan sin clave.", "already work without a key."),
                    steps = listOf(
                        tr("Crea una cuenta gratis en subdl.com.", "Create a free account at subdl.com."),
                        tr("Entra en tu panel → API y genera una clave.", "Go to your panel → API and generate a key."),
                        tr("Cópiala y escríbela aquí. Si no la quieres, pulsa «Saltar».", "Copy it and type it here. If you don't want it, press “Skip”."),
                    ),
                    required = false,
                    onSubmit = { viewModel.submitKey(ApiService.SUBDL, it) },
                    onNext = viewModel::next,
                    onTyping = viewModel::clearError,
                )
                SetupStep.DONE -> DoneStep(state = s, onFinish = onFinish)
            }
        }
        if (showServerDialog && s.step == SetupStep.WELCOME) {
            val localQrUrl by viewModel.localPairingUrl.collectAsStateWithLifecycle()
            // El servidor local vive mientras el diálogo está abierto: se abre al entrar y se cierra al salir.
            DisposableEffect(Unit) {
                viewModel.startLocalPairing(
                    onQrReady = { },
                    onVipSuccess = {
                        showServerDialog = false
                        onFinish()
                    },
                    onStandardSuccess = {
                        showServerDialog = false
                        viewModel.next()
                    },
                )
                onDispose { viewModel.stopLocalPairing() }
            }
            ServerConnectDialog(
                checking = s.checking,
                error = s.error,
                qrUrl = localQrUrl,
                onConnect = { url, pin ->
                    viewModel.connectRelay(
                        urlInput = url,
                        pinInput = pin,
                        onVipSuccess = {
                            showServerDialog = false
                            onFinish()
                        },
                        onStandardSuccess = {
                            showServerDialog = false
                            viewModel.next()
                        },
                    )
                },
                onClose = {
                    showServerDialog = false
                    viewModel.clearError()
                },
            )
        }
    }
}

private fun debridSteps(debrid: DebridService): List<String> = when (debrid) {
    DebridService.REAL_DEBRID -> listOf(
        tr("Entra en real-debrid.com con tu cuenta (necesita Premium).", "Sign in at real-debrid.com (Premium required)."),
        tr("Abre la página del token: real-debrid.com/apitoken", "Open the token page: real-debrid.com/apitoken"),
        tr("Copia el token y escríbelo aquí.", "Copy the token and type it here."),
    )
    DebridService.TORBOX -> listOf(
        tr("Entra en torbox.app con tu cuenta.", "Sign in at torbox.app."),
        tr("Abre Settings y busca tu API key.", "Open Settings and find your API key."),
        tr("Cópiala y escríbela aquí.", "Copy it and type it here."),
    )
    DebridService.PREMIUMIZE -> listOf(
        tr("Entra en premiumize.me con tu cuenta.", "Sign in at premiumize.me."),
        tr("Abre «Account» y busca la API Key.", "Open “Account” and find the API Key."),
        tr("Cópiala y escríbela aquí.", "Copy it and type it here."),
    )
    DebridService.ALLDEBRID -> listOf(
        tr("Entra en alldebrid.com con tu cuenta.", "Sign in at alldebrid.com."),
        tr("Abre la página de claves de API (alldebrid.com/apikeys) y crea una.", "Open the API keys page (alldebrid.com/apikeys) and create one."),
        tr("Cópiala y escríbela aquí.", "Copy it and type it here."),
    )
    DebridService.DEBRID_LINK -> listOf(
        tr("Entra en debrid-link.com con tu cuenta.", "Sign in at debrid-link.com."),
        tr("Abre la página de la clave de API (debrid-link.com/webapp/apikey).", "Open the API key page (debrid-link.com/webapp/apikey)."),
        tr("Cópiala y escríbela aquí.", "Copy it and type it here."),
    )
    DebridService.OFFCLOUD -> listOf(
        tr("Entra en offcloud.com con tu cuenta.", "Sign in at offcloud.com."),
        tr("Abre «Account» y busca tu API key.", "Open “Account” and find your API key."),
        tr("Cópiala y escríbela aquí.", "Copy it and type it here."),
    )
}

// ------------------------------------------------------------------ cabecera

@Composable
private fun SetupHeader(step: SetupStep) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Image(painterResource(R.drawable.bambi_logo), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Image(painterResource(R.drawable.bambi_wordmark), contentDescription = "Bambi", modifier = Modifier.height(13.dp))
        Spacer(Modifier.weight(1f))
        step.number?.let { n ->
            Text(tr("PASO $n DE 3", "STEP $n OF 3"), fontFamily = DisplayFont, fontSize = 22.sp, color = Muted)
            Spacer(Modifier.width(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..3).forEach { i ->
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(5.dp)
                            .background(if (i <= n) Accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ bienvenida

@Composable
private fun ColumnScope.WelcomeStep(
    onStart: () -> Unit,
    onServer: () -> Unit,
    onLater: () -> Unit,
    onLanguage: () -> Unit,
) {
    val startRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { startRequester.requestFocus() }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        Image(painterResource(R.drawable.bambi_logo), contentDescription = null, modifier = Modifier.size(120.dp))
        Spacer(Modifier.height(16.dp))
        Text(tr("Vamos a dejar Bambi listo", "Let's get Bambi ready"), fontFamily = DisplayFont, fontSize = 44.sp, color = Color.White)
        Text(
            tr("Son 3 pasos y solo se hace una vez. Ten a mano el móvil o el ordenador para sacar las claves.", "It takes 3 steps and you only do it once. Keep your phone or computer handy to get the keys."),
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WelcomeTile("1", tr("Catálogo", "Catalog"), tr("Portadas y sinopsis (TMDB)", "Artwork and synopses (TMDB)"))
            WelcomeTile("2", "Debrid", "Real-Debrid, TorBox…")
            WelcomeTile("3", tr("Subtítulos", "Subtitles"), tr("Opcional (SubDL)", "Optional (SubDL)"))
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(text = tr("Empezar", "Start"), onClick = onStart, modifier = Modifier.focusRequester(startRequester))
            TvButton(text = tr("Tengo un Servidor", "I have a Server"), onClick = onServer)
            TvButton(text = tr("Más tarde", "Later"), onClick = onLater)
            // Siempre en el otro idioma, para que lo entienda quien no lee el actual.
            TvButton(text = if (Lang.isEnglish) "Español" else "English", onClick = onLanguage)
        }
    }
}

@Composable
private fun WelcomeTile(number: String, title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(240.dp)
            .background(Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        StepNumber(number)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun StepNumber(text: String, color: Color = Accent, size: Dp = 30.dp) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).background(color, CircleShape)) {
        Text(text, fontFamily = DisplayFont, fontSize = (size.value * 0.6f).sp, color = Color.White)
    }
}

// ------------------------------------------------------------------ paso con clave

/**
 * Explicación + pasos + recuadro de la clave a la izquierda; QR a la derecha.
 * Foco: recuadro → ▼ botón principal; ▲ desde el recuadro → selector (paso del debrid).
 */
@Composable
private fun ColumnScope.KeyStep(
    state: SetupUiState.Ready,
    service: ApiService,
    title: String,
    intro: String,
    steps: List<String>,
    required: Boolean,
    onSubmit: (String) -> Unit,
    onNext: () -> Unit,
    onTyping: () -> Unit,
    selector: (@Composable (Modifier) -> Unit)? = null,
) {
    val has = state.has(service)
    // Con clave ya puesta se enseña "Conectada" y Siguiente; "Cambiar clave" vuelve a abrir el recuadro.
    var editing by rememberSaveable(service) { mutableStateOf(!has) }
    val detail = state.details[service]

    var input by rememberSaveable(service) { mutableStateOf("") }
    val fieldRequester = remember(service) { FocusRequester() }
    val primaryRequester = remember(service) { FocusRequester() }
    val skipRequester = remember(service) { FocusRequester() }
    val selectorRequester = remember { FocusRequester() }
    // El foco se coloca al entrar en el paso y tras cada acción (guardar, "Cambiar clave"…), pero NO al cambiar de
    // servicio con ◀▶ en el selector: si no, cada pulsación sacaría el foco del selector.
    var focusRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.step, focusRequest) {
        withFrameNanos { }
        runCatching {
            when {
                // Paso opcional sin clave: foco en "Saltar". En la tele, el teclado se abre solo con que el recuadro
                // reciba el foco, y quien quiere saltarse el paso tendría que cerrarlo primero.
                editing && !required && !has -> skipRequester.requestFocus()
                editing -> fieldRequester.requestFocus()
                else -> primaryRequester.requestFocus()
            }
        }
    }
    LaunchedEffect(detail) {
        if (detail != null && editing) {
            editing = false
            focusRequest++
        }
    }

    Row(Modifier.fillMaxWidth().weight(1f)) {
        // Desplazable por si no cabe (paso del debrid con selector): el foco lleva la vista hasta los botones.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(title.uppercase(), fontFamily = DisplayFont, fontSize = 38.sp, color = Color.White)
            Text(intro, style = MaterialTheme.typography.bodyMedium, color = Muted)
            Spacer(Modifier.height(10.dp))
            steps.forEachIndexed { i, text ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    StepNumber("${i + 1}", color = Color.White.copy(alpha = 0.14f), size = 24.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            selector?.let {
                it(Modifier.focusRequester(selectorRequester))
                Spacer(Modifier.height(10.dp))
            }
            if (editing) {
                KeyField(
                    value = input,
                    onValueChange = {
                        input = it
                        onTyping()
                    },
                    enabled = !state.checking,
                    placeholder = tr("${service.keyName} de ${service.title}", "${service.title} ${service.keyName}"),
                    onDone = { onSubmit(input) },
                    modifier = Modifier
                        .focusRequester(fieldRequester)
                        .focusProperties { if (selector != null) up = selectorRequester },
                    downRequester = primaryRequester,
                )
                Spacer(Modifier.height(10.dp))
                StatusLine(state, service)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        text = if (state.checking) tr("Comprobando…", "Checking…") else tr("Comprobar y guardar", "Check and save"),
                        onClick = { onSubmit(input) },
                        enabled = !state.checking,
                        modifier = Modifier.focusRequester(primaryRequester),
                    )
                    when {
                        has -> TvButton(text = tr("Dejar la que hay", "Keep the current one"), onClick = {
                            editing = false
                            focusRequest++
                        })
                        !required -> TvButton(text = tr("Saltar", "Skip"), onClick = onNext, modifier = Modifier.focusRequester(skipRequester))
                    }
                }
            } else {
                ConfiguredRow(service = service, maskedKey = state.keys[service], detail = detail)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        text = tr("Siguiente", "Next"),
                        onClick = onNext,
                        modifier = Modifier
                            .focusRequester(primaryRequester)
                            .focusProperties { if (selector != null) up = selectorRequester },
                    )
                    TvButton(text = tr("Cambiar clave", "Change key"), onClick = {
                        input = ""
                        editing = true
                        focusRequest++
                    })
                }
            }
        }
        Spacer(Modifier.width(36.dp))
        QrPanel(url = service.url)
    }
}

@Composable
private fun StatusLine(state: SetupUiState.Ready, service: ApiService) {
    val error = state.error
    val (text, color) = when {
        state.checking -> tr("Comprobando con ${service.title}…", "Checking with ${service.title}…") to Muted
        error != null -> error to Red
        else -> tr("Pulsa OK sobre el recuadro para escribir. Se comprueba con ${service.title} antes de guardarla.", "Press OK on the box to type. It's checked with ${service.title} before saving.") to Muted
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun ConfiguredRow(service: ApiService, maskedKey: String?, detail: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Green.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).background(Green, CircleShape)) {
            Text("✓", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                tr("${service.title} configurado", "${service.title} set up"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(
                listOfNotNull(detail, maskedKey?.let { tr("clave ····${it.takeLast(4)}", "key ····${it.takeLast(4)}") }).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}

/** Recuadro de clave: OK abre el teclado de la tele; ▼ baja a los botones (el campo se queda las flechas). */
@Composable
private fun KeyField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    onDone: () -> Unit,
    downRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    // El teclado solo se abre si el OK se pulsó (y soltó) aquí: el OK de "Siguiente" del paso anterior suelta
    // la tecla ya con el foco en este recuadro y, si no, abría el teclado sin querer.
    var pressedHere by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "").trim()) },
        singleLine = true,
        enabled = enabled,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            // Compose abre el teclado solo con recibir el foco: aquí solo se abre con OK (ver onPreviewKeyEvent).
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            onDone()
        }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (event.type == KeyEventType.KeyDown) pressedHere = true
                        if (event.type == KeyEventType.KeyUp && pressedHere) {
                            pressedHere = false
                            keyboard?.show()
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) runCatching { downRequester.requestFocus() }
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
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) Color.White else Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 18.dp, vertical = 15.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        if (focused) tr("Pulsa OK para escribir", "Press OK to type") else placeholder,
                        fontSize = 18.sp,
                        color = Color.White.copy(alpha = 0.35f),
                    )
                }
                field()
            }
        },
    )
}

/** QR que abre en el móvil la página donde se saca la clave (más cómodo que teclear la dirección). */
@Composable
private fun QrPanel(url: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(250.dp)
            .background(Panel, RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(190.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            QrCode(url, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        Text(
            tr("Escanéalo con la cámara del móvil", "Scan it with your phone's camera"),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            url.removePrefix("https://").removePrefix("www."),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun QrCode(url: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(url) {
        runCatching {
            QRCodeWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    Canvas(modifier) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
            }
        }
    }
}

// ------------------------------------------------------------------ final

@Composable
private fun ColumnScope.DoneStep(state: SetupUiState.Ready, onFinish: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp).background(Green, CircleShape)) {
            Text("✓", color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("¡Todo listo!", "All set!"), fontFamily = DisplayFont, fontSize = 48.sp, color = Color.White)
        Text(
            tr("Puedes cambiar cualquier clave cuando quieras en Ajustes (la rueda de arriba a la derecha).", "You can change any key whenever you like in Settings (the gear at the top right)."),
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
        )
        Spacer(Modifier.height(22.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(560.dp),
        ) {
            SummaryRow(tr("Catálogo · TMDB", "Catalog · TMDB"), state.has(ApiService.TMDB), required = true)
            SummaryRow("Debrid · ${state.debrid.title}", state.has(state.debrid.apiService), required = true)
            SummaryRow(tr("Subtítulos extra · SubDL", "Extra subtitles · SubDL"), state.has(ApiService.SUBDL), required = false)
        }
        Spacer(Modifier.height(26.dp))
        TvButton(text = tr("Empezar a ver", "Start watching"), onClick = onFinish, modifier = Modifier.focusRequester(requester))
    }
}

@Composable
private fun SummaryRow(label: String, ok: Boolean, required: Boolean) {
    val (mark, color, note) = when {
        ok -> Triple("✓", Green, tr("Configurado", "Set up"))
        required -> Triple("!", Red, tr("Falta: sin esto no funcionará", "Missing: it won't work without this"))
        else -> Triple("–", Muted, tr("Sin configurar (opcional)", "Not set up (optional)"))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(24.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
        Text(note, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

// ------------------------------------------------------------------ diálogo servidor

/**
 * Vincular la TV con el servidor privado.
 *
 * Izquierda: QR con el formulario local (el móvil, en la misma Wi-Fi, escribe URL y PIN
 * y la TV los recibe sola). Derecha: los mismos campos a mano, por si se prefiere el mando.
 */
@Composable
private fun ServerConnectDialog(
    checking: Boolean,
    error: String?,
    qrUrl: String?,
    onConnect: (url: String, pin: String) -> Unit,
    onClose: () -> Unit,
) {
    var urlInput by rememberSaveable { mutableStateOf("https://") }
    var pinInput by rememberSaveable { mutableStateOf("") }
    val urlRequester = remember { FocusRequester() }
    val pinRequester = remember { FocusRequester() }
    val connectRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { urlRequester.requestFocus() }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .onBackKey(enabled = true) { onClose() },
    ) {
        Column(
            Modifier
                .width(860.dp)
                .background(Color(0xFF161922), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(28.dp),
        ) {
            Text(
                tr("Conectar a Servidor Privado", "Connect to Private Server"),
                fontFamily = DisplayFont,
                fontSize = 24.sp,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Vincúlala desde el móvil en 10 segundos, o escribe la dirección y el PIN con el mando.",
                    "Pair it from your phone in 10 seconds, or type the address and PIN with the remote.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                // ---------------- columna izquierda: QR
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(220.dp)
                        .background(Color(0xFF101119), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(160.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp),
                    ) {
                        if (qrUrl.isNullOrBlank()) {
                            Text(
                                tr("Preparando…", "Starting…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Black.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            QrCode(qrUrl, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tr(
                            "Escanea con tu móvil en la misma Wi-Fi para vincular sin mando",
                            "Scan with your phone on the same Wi-Fi to pair without the remote",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        textAlign = TextAlign.Center,
                    )
                }

                // ---------------- columna derecha: campos manuales
                Column(Modifier.weight(1f)) {
                    Text(tr("URL del Servidor", "Server URL"), style = MaterialTheme.typography.labelMedium, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    var urlFocused by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it.replace("\n", "") },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { pinRequester.requestFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(urlRequester)
                            .onFocusChanged { urlFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (event.type == KeyEventType.KeyUp) keyboard?.show()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (event.type == KeyEventType.KeyDown) runCatching { pinRequester.requestFocus() }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0D0F15), RoundedCornerShape(10.dp))
                                    .border(1.dp, if (urlFocused) Accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                inner()
                            }
                        },
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(tr("PIN (6 cifras)", "PIN (6 digits)"), style = MaterialTheme.typography.labelMedium, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    var pinFocused by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(6) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                        cursorBrush = SolidColor(Color.White),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            connectRequester.requestFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(pinRequester)
                            .onFocusChanged { pinFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (event.type == KeyEventType.KeyUp) keyboard?.show()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (event.type == KeyEventType.KeyDown) runCatching { connectRequester.requestFocus() }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (event.type == KeyEventType.KeyDown) runCatching { urlRequester.requestFocus() }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0D0F15), RoundedCornerShape(10.dp))
                                    .border(1.dp, if (pinFocused) Accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                if (pinInput.isEmpty()) {
                                    Text("••••••", color = Color.White.copy(alpha = 0.25f), fontSize = 20.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
                                }
                                inner()
                            }
                        },
                    )

                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = Red)
                    }

                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = if (checking) tr("Conectando...", "Connecting...") else tr("Conectar", "Connect"),
                            onClick = {
                                keyboard?.hide()
                                if (!checking) onConnect(urlInput, pinInput)
                            },
                            enabled = !checking,
                            modifier = Modifier.focusRequester(connectRequester),
                        )
                        TvButton(
                            text = tr("Cancelar", "Cancel"),
                            onClick = {
                                keyboard?.hide()
                                onClose()
                            },
                            modifier = Modifier.focusRequester(cancelRequester),
                        )
                    }
                }
            }
        }
    }
}
