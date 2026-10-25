package com.shieldstream.tv.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Scroll "pivot" estilo Netflix: el elemento enfocado se coloca a [leadingPx] del borde
 * inicial del contenedor, en vez del comportamiento por defecto (scroll mínimo hasta el borde).
 * Es lo que hace que la tarjeta enfocada se quede fija a la izquierda y las filas suban.
 */
@OptIn(ExperimentalFoundationApi::class)
private class PivotBringIntoViewSpec(private val leadingPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val childSize = abs(size)
        val target = if (childSize <= containerSize && containerSize - leadingPx < childSize) {
            containerSize - childSize
        } else {
            leadingPx
        }
        return offset - target
    }
}

/** Aplica el scroll pivot a los LazyRow/LazyColumn de [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvidePivotScroll(leading: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { leading.toPx() }
    val spec = remember(px) { PivotBringIntoViewSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Scroll mínimo: solo desplaza lo justo para que el elemento enfocado se vea.
 * En Android TV Compose usa por defecto un "pivot" que centra el foco, y en pantallas
 * con cabecera (detalle) eso esconde el logo nada más entrar.
 */
@OptIn(ExperimentalFoundationApi::class)
private class MinimalBringIntoViewSpec(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, rawSize: Float, containerSize: Float): Float {
        // Margen alrededor del elemento enfocado (p. ej. para que se vea el título bajo la miniatura).
        val offset = offset - marginPx
        val size = rawSize + 2 * marginPx
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideMinimalScroll(margin: Dp = 0.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { margin.toPx() }
    val spec = remember(px) { MinimalBringIntoViewSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Atrás del mando interceptado ANTES de que lo procese el sistema de foco.
 * Compose usa el primer Atrás para "salir" del grupo de foco (el elemento enfocado pierde el foco)
 * y el BackHandler no se entera: parece que la pantalla se queda bloqueada.
 * Se consumen KeyDown y KeyUp para que la Activity no lo procese además.
 */
fun Modifier.onBackKey(enabled: Boolean, onBack: () -> Unit): Modifier = onKeyEvent { event ->
    // onKeyEvent va del elemento enfocado hacia fuera: el nivel más interno (p. ej. "Subtítulos externos")
    // se cierra antes que el panel que lo contiene, y todo ocurre antes de que Compose mueva el foco.
    if (!enabled || (event.key != Key.Back && event.key != Key.Escape)) return@onKeyEvent false
    if (event.type == KeyEventType.KeyUp) onBack()
    true
}

/** clickable sin ripple: en TV el foco ya se marca con escala/borde. Responde a D-pad center/Enter. */
fun Modifier.tvClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)

/** Igual que [tvClickable], con pulsación larga (mantener OK) si [onLongClick] no es null. */
fun Modifier.tvClickable(onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier =
    if (onLongClick == null) {
        tvClickable(onClick = onClick)
    } else {
        combinedClickable(interactionSource = null, indication = null, onLongClick = onLongClick, onClick = onClick)
    }
