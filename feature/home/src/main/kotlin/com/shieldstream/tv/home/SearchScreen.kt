package com.shieldstream.tv.home

import com.shieldstream.tv.model.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.PosterCard
import com.shieldstream.tv.ui.component.tvClickable
import kotlinx.coroutines.delay

private const val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890"
private const val KEY_COLUMNS = 6

/** QWERTY (con Ñ, como un teclado español). Filas de distinta longitud: el D-pad va al más cercano. */
private val QWERTY_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjklñ", "zxcvbnm")

/**
 * Búsqueda estilo Netflix TV: teclado propio en pantalla (evita el IME del sistema, que en TV
 * atrapa el foco del D-pad) + resultados en rejilla que se actualizan mientras escribes.
 */
@Composable
fun SearchScreen(
    onItemClick: (CatalogItem) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val qwerty by viewModel.qwerty.collectAsStateWithLifecycle()

    val firstKey = remember { FocusRequester() }
    val layoutKey = remember { FocusRequester() }
    val cardRequester = remember { FocusRequester() }
    // Última tarjeta enfocada: al volver de una ficha (Atrás), el foco vuelve a ella y no al teclado.
    // rememberSaveable: sobrevive mientras la pantalla está debajo en la pila de navegación.
    var lastCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val backOnCard = lastCardKey != null && run {
            // La rejilla puede tardar un fotograma en colocar la tarjeta: unos reintentos cortos.
            repeat(4) {
                if (runCatching { cardRequester.requestFocus() }.isSuccess) return@run true
                delay(60)
            }
            false
        }
        if (!backOnCard) runCatching { firstKey.requestFocus() }
    }

    // Teclado del sistema (incluye el dictado por voz del mando en muchas teles). Solo se puede enfocar el
    // campo mientras está abierto: el teclado de Android en TV se queda con el D-pad y no deja salir.
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldRequester = remember { FocusRequester() }
    var systemKeyboard by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // Solo con el teclado de Android cerrado: mientras escribe, manda el campo (si no, un valor de query aún sin
    // actualizar pisaría lo que se acaba de teclear y se comería letras).
    LaunchedEffect(query, systemKeyboard) {
        if (!systemKeyboard && field.text != query) field = TextFieldValue(query, TextRange(query.length))
    }
    LaunchedEffect(systemKeyboard) {
        if (systemKeyboard) {
            withFrameNanos { }
            runCatching { fieldRequester.requestFocus() }
            keyboard?.show()
        }
    }
    fun closeSystemKeyboard() {
        keyboard?.hide()
        systemKeyboard = false
        runCatching { layoutKey.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier
                .width(if (qwerty) 452.dp else 330.dp)
                .fillMaxHeight()
                .padding(start = 40.dp, top = 40.dp, end = 16.dp),
        ) {
            // Campo de búsqueda: muestra el texto. Se escribe con el teclado de abajo o, con "Teclado del sistema",
            // con el de Android (entonces es un campo de texto de verdad).
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1C1C1F))
                    .then(if (systemKeyboard) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            viewModel.setQuery(it.text.replace("\n", ""))
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { closeSystemKeyboard() }, onDone = { closeSystemKeyboard() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldRequester)
                            .focusProperties { canFocus = systemKeyboard }
                            // Con el teclado de Android ya cerrado, el campo se quedaría con las flechas (mueven el
                            // cursor): ▼ o Atrás salen del campo y vuelven al teclado de Bambi.
                            .onPreviewKeyEvent { event ->
                                val exit = event.key == Key.DirectionDown || event.key == Key.Back || event.key == Key.Escape
                                if (!exit) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyUp) closeSystemKeyboard()
                                true
                            }
                            .onFocusChanged { if (!it.isFocused && systemKeyboard) systemKeyboard = false },
                    )
                    if (query.isEmpty()) {
                        Text(
                            text = tr("Buscar", "Search"),
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OnScreenKeyboard(
                qwerty = qwerty,
                firstKeyRequester = firstKey,
                layoutKeyRequester = layoutKey,
                onChar = viewModel::type,
                onBackspace = viewModel::backspace,
                onClear = viewModel::clear,
                onToggleLayout = viewModel::toggleKeyboardLayout,
                onSystemKeyboard = { systemKeyboard = true },
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 40.dp, end = 24.dp)) {
            val header = when (val s = state) {
                SearchUiState.Loading -> tr("Buscando…", "Searching…")
                is SearchUiState.Suggestions -> tr("Lo más visto hoy", "Most watched today")
                is SearchUiState.Results -> tr("Resultados para «${s.query}»", "Results for “${s.query}”")
                is SearchUiState.Empty -> tr("Sin resultados para «${s.query}»", "No results for “${s.query}”")
                is SearchUiState.Error -> s.message
            }
            Text(
                text = header,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            val items = when (val s = state) {
                is SearchUiState.Suggestions -> s.items
                is SearchUiState.Results -> s.items
                else -> emptyList()
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.key }) { item ->
                    PosterCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onFocused = { lastCardKey = item.key },
                        width = 118.dp,
                        modifier = if (item.key == lastCardKey) Modifier.focusRequester(cardRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnScreenKeyboard(
    qwerty: Boolean,
    firstKeyRequester: FocusRequester,
    layoutKeyRequester: FocusRequester,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onToggleLayout: () -> Unit,
    onSystemKeyboard: () -> Unit,
) {
    // Al cambiar ABC ⇄ QWERTY el foco se queda en la tecla de cambiar (si no, se perdería al rehacer las filas).
    var toggled by remember { mutableStateOf(false) }
    LaunchedEffect(qwerty) {
        if (toggled) {
            withFrameNanos { }
            runCatching { layoutKeyRequester.requestFocus() }
        }
    }
    val rows = if (qwerty) QWERTY_ROWS else KEYS.chunked(KEY_COLUMNS)
    val keyWidth = if (qwerty) 36.dp else 42.dp
    // Ancho total de una fila completa (6 o 10 teclas): las filas de abajo se reparten ese ancho.
    val fullWidth = if (qwerty) 36.dp * 10 + 4.dp * 9 else 42.dp * 6 + 4.dp * 5
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { rowIndex, rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowKeys.forEachIndexed { colIndex, c ->
                    val mod = if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstKeyRequester) else Modifier
                    KeyButton(width = keyWidth, onClick = { onChar(c) }, modifier = mod) {
                        Text(c.uppercase(), style = MaterialTheme.typography.titleMedium, color = it)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton(width = fullWidth - 42.dp - 88.dp - 8.dp, onClick = { onChar(' ') }) {
                Text(tr("Espacio", "Space"), style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(width = 42.dp, onClick = onBackspace) {
                Icon(AppIcons.Backspace, contentDescription = tr("Borrar", "Clear"), tint = it, modifier = Modifier.size(20.dp))
            }
            KeyButton(width = 88.dp, onClick = onClear) {
                Text(tr("Borrar", "Clear"), style = MaterialTheme.typography.bodyMedium, color = it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton(
                width = 88.dp,
                onClick = {
                    toggled = true
                    onToggleLayout()
                },
                modifier = Modifier.focusRequester(layoutKeyRequester),
            ) {
                Text(if (qwerty) "ABC" else "QWERTY", style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(width = fullWidth - 88.dp - 4.dp, onClick = onSystemKeyboard) {
                Text(tr("Teclado del sistema", "System keyboard"), style = MaterialTheme.typography.bodyMedium, color = it, maxLines = 1)
            }
        }
    }
}

@Composable
private fun KeyButton(
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(width)
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White else Color(0xFF1C1C1F))
            .then(if (focused) Modifier else Modifier.border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(4.dp)))
            .tvClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (focused) Color.Black else Color.White)
    }
}
