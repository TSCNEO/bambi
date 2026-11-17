package com.shieldstream.tv.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldstream.tv.model.tr
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.theme.DisplayFont

private val DialogMuted = Color.White.copy(alpha = 0.62f)
private val DialogRed = Color(0xFFFF5A5F)
private val DialogAccent = Color(0xFFE50914)

/**
 * Vincular la TV con el servidor privado.
 *
 * Izquierda: QR con el formulario local (el móvil, en la misma Wi-Fi, escribe URL y PIN
 * y la TV los recibe sola). Derecha: los mismos campos a mano, por si se prefiere el mando.
 */
@Composable
internal fun ServerConnectDialog(
    checking: Boolean,
    error: String?,
    qrUrl: String?,
    onConnect: (url: String, pin: String) -> Unit,
    onClose: () -> Unit,
) {
    var urlInput by rememberSaveable { mutableStateOf("") }
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
                color = DialogMuted,
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
                        color = DialogMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                // ---------------- columna derecha: campos manuales
                Column(Modifier.weight(1f)) {
                    Text(tr("URL del Servidor", "Server URL"), style = MaterialTheme.typography.labelMedium, color = DialogMuted)
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
                                    .border(1.dp, if (urlFocused) DialogAccent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                if (urlInput.isEmpty()) {
                                    Text("mi-servidor.com", color = Color.White.copy(alpha = 0.25f), fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                                }
                                inner()
                            }
                        },
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(tr("PIN", "PIN"), style = MaterialTheme.typography.labelMedium, color = DialogMuted)
                    Spacer(Modifier.height(6.dp))
                    var pinFocused by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter { c -> c.isDigit() } },
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
                                    .border(1.dp, if (pinFocused) DialogAccent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                if (pinInput.isEmpty()) {
                                    Text("PIN", color = Color.White.copy(alpha = 0.25f), fontSize = 20.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                                }
                                inner()
                            }
                        },
                    )

                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = DialogRed)
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
