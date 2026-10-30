package com.shieldstream.tv.settings

import com.shieldstream.tv.model.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import kotlin.random.Random

/**
 * Servidor HTTP efímero dentro de la TV para vincularla sin mando.
 *
 * Abre un puerto aleatorio de 5 cifras (10000..59999), enseña un QR con
 * `http://<ip-local>:<puerto>` y sirve un formulario donde el móvil (misma Wi-Fi)
 * escribe la URL del servidor y el PIN. Al recibir el POST se avisa con
 * [onCredentials] y el servidor se apaga solo.
 *
 * Todo el tráfico es de un solo uso y no sale de la red local.
 */
class LocalPairingServer(
    /** Se llama (en hilo de fondo) cuando el móvil envía URL + PIN. */
    private val onCredentials: (url: String, pin: String) -> Unit,
    /** URL lista para el QR, p. ej. `http://192.168.1.50:31415`. */
    private val onReady: (String) -> Unit,
    /** No hay red local o el puerto no se pudo abrir. */
    private val onError: (String) -> Unit = {},
) {

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /** URL pública del formulario mientras el servidor está vivo. */
    var url: String? = null
        private set

    val isRunning: Boolean get() = running

    /** Arranca en segundo plano; [onReady] llega cuando el puerto ya escucha. */
    fun start() {
        if (running) return
        running = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val ip = findLocalIpv4Address()
            if (ip == null) {
                running = false
                onError(tr("La TV no está conectada a ninguna red local", "The TV is not connected to a local network"))
                return@launch
            }
            val server = runCatching { openServerSocket() }.getOrElse {
                running = false
                onError(tr("No se pudo abrir el servidor local", "Could not open the local server"))
                return@launch
            }
            serverSocket = server
            val localUrl = "http://$ip:${server.localPort}"
            url = localUrl
            onReady(localUrl)

            try {
                while (running) {
                    val client = try {
                        server.accept()
                    } catch (_: Throwable) {
                        // socket cerrado por stop() o error de red: se deja de escuchar
                        break
                    }
                    newScope.launch { handleConnection(client) }
                }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    /** Cierra el puerto y cancela el hilo de escucha. Idempotente. */
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        url = null
        scope?.cancel()
        scope = null
    }

    // ------------------------------------------------------------------ red

    /** Puerto de 5 cifras al azar; si está ocupado reintenta y acaba en uno efímero. */
    private fun openServerSocket(): ServerSocket {
        repeat(PORT_ATTEMPTS) {
            val port = Random.nextInt(PORT_MIN, PORT_MAX + 1)
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port), BACKLOG)
                return socket
            } catch (_: Throwable) {
                runCatching { socket.close() }
            }
        }
        // Último recurso: el sistema elige el puerto (Android usa 32768..60999).
        return ServerSocket(0).apply {
            runCatching { reuseAddress = true }
        }
    }

    /**
     * IPv4 local de la TV. Prioriza wlan (Wi-Fi), luego eth/en (cable) y
     * por último cualquier interfaz con dirección privada.
     */
    private fun findLocalIpv4Address(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        val siteLocal = ArrayList<Pair<Int, String>>()
        val anyIpv4 = ArrayList<Pair<Int, String>>()

        for (iface in Collections.list(interfaces)) {
            if (!runCatching { iface.isUp }.getOrDefault(false)) continue
            if (runCatching { iface.isLoopback }.getOrDefault(true)) continue
            val priority = when {
                iface.name.startsWith("wlan", ignoreCase = true) -> 0
                iface.name.startsWith("eth", ignoreCase = true) -> 1
                iface.name.startsWith("en", ignoreCase = true) -> 2
                else -> 3
            }
            for (address in Collections.list(iface.inetAddresses)) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress) continue
                val host = address.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: continue
                if (address.isSiteLocalAddress) siteLocal += priority to host
                if (!address.isLinkLocalAddress) anyIpv4 += priority to host
            }
        }
        return (siteLocal.minByOrNull { it.first } ?: anyIpv4.minByOrNull { it.first })?.second
    }

    // ------------------------------------------------------------------ HTTP

    private fun handleConnection(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val request = readRequest(socket.getInputStream()) ?: return
                val output = socket.getOutputStream()
                when {
                    request.method == "POST" && request.path == "/submit" -> handleSubmit(request, output)
                    request.method == "GET" && (request.path == "/" || request.path.startsWith("/?")) ->
                        respond(output, 200, "OK", "text/html; charset=utf-8", PAGE)
                    request.path == "/favicon.ico" ->
                        respond(output, 204, "No Content", "text/plain", "")
                    else ->
                        respond(output, 404, "Not Found", "text/plain; charset=utf-8", "Not found")
                }
                output.flush()
            }
        }
    }

    private fun handleSubmit(request: HttpRequest, output: OutputStream) {
        val credentials = parseCredentials(String(request.body, Charsets.UTF_8))
        if (credentials == null) {
            respond(output, 400, "Bad Request", JSON_TYPE, errorJson(tr("Escribe la URL y el PIN de 6 cifras", "Enter the URL and the 6-digit PIN")))
            output.flush()
            return
        }
        val (url, pin) = credentials
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            respond(output, 400, "Bad Request", JSON_TYPE, errorJson(tr("La URL debe empezar por http:// o https://", "The URL must start with http:// or https://")))
            output.flush()
            return
        }
        if (pin.length != 6) {
            respond(output, 400, "Bad Request", JSON_TYPE, errorJson(tr("El PIN debe tener 6 cifras", "The PIN must have 6 digits")))
            output.flush()
            return
        }

        respond(output, 200, "OK", JSON_TYPE, """{"ok":true}""")
        output.flush()
        // El móvil ya tiene su confirmación: se avisa a la TV y se apaga el servidor.
        onCredentials(url, pin)
        stop()
    }

    /** Acepta el JSON del formulario y también `application/x-www-form-urlencoded`. */
    private fun parseCredentials(body: String): Pair<String, String>? {
        val text = body.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val pin = obj["pin"]?.jsonPrimitive?.contentOrNull?.trim()?.filter { it.isDigit() }.orEmpty()
            return if (url.isEmpty() || pin.isEmpty()) null else url to pin
        }
        val params = text.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = decode(part.substring(0, idx))
            val value = decode(part.substring(idx + 1))
            key to value
        }.toMap()
        val url = params["url"].orEmpty().trim()
        val pin = params["pin"].orEmpty().trim().filter { it.isDigit() }
        return if (url.isEmpty() || pin.isEmpty()) null else url to pin
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun errorJson(message: String): String =
        """{"ok":false,"error":"${message.replace("\"", "'")}"}"""

    private fun respond(output: OutputStream, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store, max-age=0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val header = ByteArrayOutputStream()
        var matched = 0
        while (header.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            header.write(b)
            matched = when {
                b.toByte() == CRLF_SEQUENCE[matched] -> matched + 1
                b.toByte() == CRLF_SEQUENCE[0] -> 1
                else -> 0
            }
            if (matched == CRLF_SEQUENCE.size) break
        }
        if (matched != CRLF_SEQUENCE.size) return null

        val lines = header.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = HashMap<String, String>(8)
        lines.drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val bodyLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val body = ByteArray(bodyLength)
        var read = 0
        while (read < bodyLength) {
            val n = input.read(body, read, bodyLength - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(
            method = parts[0].uppercase(),
            path = parts[1].substringBefore('?').ifEmpty { "/" },
            headers = headers,
            body = if (read == bodyLength) body else body.copyOf(read),
        )
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private companion object {
        const val PORT_MIN = 10000
        const val PORT_MAX = 59999
        const val PORT_ATTEMPTS = 20
        const val BACKLOG = 8
        const val SOCKET_TIMEOUT_MS = 5_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 8 * 1024
        const val JSON_TYPE = "application/json; charset=utf-8"
        val CRLF_SEQUENCE = byteArrayOf(13, 10, 13, 10)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Formulario que ve el móvil. Sin `$` para no chocar con la plantilla de Kotlin. */
        val PAGE = """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#151622">
<title>Bambi · Vincular con la TV</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
    padding: 24px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: radial-gradient(120% 90% at 50% 0%, #1d2030 0%, #0b0c12 60%, #000 100%);
    color: #fff;
  }
  .card {
    width: 100%; max-width: 420px;
    background: #161922; border: 1px solid rgba(255,255,255,0.10); border-radius: 22px;
    padding: 26px 22px 24px; box-shadow: 0 24px 60px rgba(0,0,0,0.55);
  }
  .brand { display: flex; align-items: center; gap: 8px; font-size: 13px; letter-spacing: 3px; color: #e50914; font-weight: 800; }
  h1 { margin: 14px 0 8px; font-size: 26px; line-height: 1.15; }
  p.lead { margin: 0 0 22px; font-size: 15px; line-height: 1.45; color: rgba(255,255,255,0.66); }
  label { display: block; font-size: 12px; text-transform: uppercase; letter-spacing: 1.2px; color: rgba(255,255,255,0.55); margin: 16px 0 7px; }
  input {
    width: 100%; padding: 14px 15px; font-size: 17px; color: #fff;
    background: #0d0f15; border: 1px solid rgba(255,255,255,0.12); border-radius: 12px;
    outline: none; transition: border-color .15s, box-shadow .15s;
  }
  input:focus { border-color: #e50914; box-shadow: 0 0 0 3px rgba(229,9,20,0.18); }
  #url { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 15px; }
  #pin { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 21px; letter-spacing: 7px; text-align: center; }
  button {
    width: 100%; margin-top: 26px; padding: 16px; font-size: 17px; font-weight: 700; color: #fff;
    background: #e50914; border: 0; border-radius: 12px; cursor: pointer;
    transition: transform .08s, opacity .15s;
  }
  button:active { transform: scale(0.985); }
  button[disabled] { opacity: .6; cursor: default; }
  .hint { margin: 14px 0 0; font-size: 12.5px; line-height: 1.5; color: rgba(255,255,255,0.42); text-align: center; }
  .err { margin: 14px 0 0; padding: 11px 13px; font-size: 14px; color: #ff8a8e; background: rgba(255,90,95,0.10); border-radius: 10px; }
  .ok { margin-top: 6px; text-align: center; }
  .ok .tick {
    width: 64px; height: 64px; margin: 0 auto 16px; border-radius: 50%;
    display: flex; align-items: center; justify-content: center; font-size: 34px; color: #06240f;
    background: #46d369; font-weight: 800;
  }
  .ok h2 { margin: 0 0 8px; font-size: 22px; }
  .ok p { margin: 0; font-size: 14.5px; color: rgba(255,255,255,0.6); line-height: 1.5; }
  [hidden] { display: none !important; }
</style>
</head>
<body>
<main class="card">
  <div class="brand">BAMBI</div>
  <h1>Vincular con la TV</h1>
  <p class="lead">Escribe la dirección de tu servidor y tu PIN de 6 cifras. La TV los recibe al instante, sin tocar el mando.</p>

  <form id="f" autocomplete="off" novalidate>
    <label for="url">URL del servidor</label>
    <input id="url" name="url" type="text" inputmode="url" placeholder="https://mi-servidor.com"
           autocapitalize="off" autocorrect="off" spellcheck="false" enterkeyhint="next">

    <label for="pin">PIN de 6 cifras</label>
    <input id="pin" name="pin" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="6"
           placeholder="000000" enterkeyhint="done">

    <button id="btn" type="submit">Vincular con la TV</button>
    <p class="hint">Misma Wi-Fi que la TV. Esta página se apaga sola al enviar los datos.</p>
    <p id="err" class="err" hidden></p>
  </form>

  <div id="ok" class="ok" hidden>
    <div class="tick">&#10003;</div>
    <h2>&iexcl;Datos enviados a la TV!</h2>
    <p>Mira la pantalla del televisor: la configuraci&oacute;n contin&uacute;a all&iacute;. Ya puedes cerrar esta p&aacute;gina.</p>
  </div>
</main>

<script>
(function () {
  var form = document.getElementById('f');
  var btn = document.getElementById('btn');
  var errBox = document.getElementById('err');
  var okBox = document.getElementById('ok');

  function showError(message) {
    errBox.textContent = message;
    errBox.hidden = false;
  }

  var pinInput = document.getElementById('pin');

  // maxlength recorta antes que el saneado, así que los caracteres que no son cifras
  // se frenan aquí: si no, un dedo torcido en el teclado se comería una cifra del PIN.
  pinInput.addEventListener('beforeinput', function (event) {
    if (!event.data || !/[^0-9]/.test(event.data)) return;
    event.preventDefault();
    var digits = event.data.replace(/[^0-9]/g, '');
    var start = pinInput.selectionStart === null ? pinInput.value.length : pinInput.selectionStart;
    var end = pinInput.selectionEnd === null ? start : pinInput.selectionEnd;
    pinInput.value = (pinInput.value.slice(0, start) + digits + pinInput.value.slice(end)).slice(0, 6);
    var caret = Math.min(start + digits.length, 6);
    pinInput.setSelectionRange(caret, caret);
  });

  pinInput.addEventListener('input', function () {
    var clean = pinInput.value.replace(/[^0-9]/g, '').slice(0, 6);
    if (clean !== pinInput.value) pinInput.value = clean;
  });

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    errBox.hidden = true;

    var url = document.getElementById('url').value.trim().replace(/\/+$/, '');
    var pin = document.getElementById('pin').value.replace(/[^0-9]/g, '');

    if (!url) { showError('Escribe la URL del servidor.'); return; }
    if (!/^https?:\/\//i.test(url)) { showError('La URL debe empezar por http:// o https://'); return; }
    if (pin.length !== 6) { showError('El PIN debe tener 6 cifras.'); return; }

    btn.disabled = true;
    btn.textContent = 'Enviando...';

    fetch('/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: url, pin: pin })
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) {
        if (!response.ok) { throw new Error(data.error || ('Error ' + response.status)); }
        return data;
      });
    }).then(function () {
      form.hidden = true;
      okBox.hidden = false;
    }).catch(function (error) {
      btn.disabled = false;
      btn.textContent = 'Vincular con la TV';
      showError((error && error.message) ? error.message : 'No se pudo enviar. Int\u00e9ntalo otra vez.');
    });
  });
})();
</script>
</body>
</html>
"""
    }
}
