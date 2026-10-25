package com.shieldstream.tv.home

import com.shieldstream.tv.model.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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

private const val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890"
private const val KEY_COLUMNS = 6

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

    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier
                .width(330.dp)
                .fillMaxHeight()
                .padding(start = 40.dp, top = 40.dp, end = 16.dp),
        ) {
            // "Campo" de búsqueda: solo muestra el texto; se escribe con el teclado de abajo.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1C1C1F))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = query.ifEmpty { tr("Buscar", "Search") },
                    color = if (query.isEmpty()) Color.White.copy(alpha = 0.45f) else Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            OnScreenKeyboard(
                firstKeyRequester = firstKey,
                onChar = viewModel::type,
                onBackspace = viewModel::backspace,
                onClear = viewModel::clear,
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
                    PosterCard(item = item, onClick = { onItemClick(item) }, width = 118.dp)
                }
            }
        }
    }
}

@Composable
private fun OnScreenKeyboard(
    firstKeyRequester: FocusRequester,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KEYS.chunked(KEY_COLUMNS).forEachIndexed { rowIndex, rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowKeys.forEachIndexed { colIndex, c ->
                    val mod = if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstKeyRequester) else Modifier
                    KeyButton(width = 42.dp, onClick = { onChar(c) }, modifier = mod) {
                        Text(c.uppercase(), style = MaterialTheme.typography.titleMedium, color = it)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton(width = 130.dp, onClick = { onChar(' ') }) {
                Text(tr("Espacio", "Space"), style = MaterialTheme.typography.bodyMedium, color = it)
            }
            KeyButton(width = 42.dp, onClick = onBackspace) {
                Icon(AppIcons.Backspace, contentDescription = tr("Borrar", "Clear"), tint = it, modifier = Modifier.size(20.dp))
            }
            KeyButton(width = 88.dp, onClick = onClear) {
                Text(tr("Borrar", "Clear"), style = MaterialTheme.typography.bodyMedium, color = it)
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
