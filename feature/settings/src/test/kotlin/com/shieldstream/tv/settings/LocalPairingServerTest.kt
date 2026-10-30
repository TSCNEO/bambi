package com.shieldstream.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pruebas de verdad contra un socket: arranca el servidor, pide el formulario y envía
 * el POST como haría el móvil.
 */
class LocalPairingServerTest {

    /** Abre el servidor de la prueba y devuelve su URL base (http://ip:puerto). */
    private fun startServer(
        onCredentials: (String, String) -> Unit = { _, _ -> },
    ): Pair<LocalPairingServer, String> {
        val latch = CountDownLatch(1)
        val baseUrl = AtomicReference<String>()
        val server = LocalPairingServer(
            onCredentials = { url, pin -> onCredentials(url, pin) },
            onReady = { baseUrl.set(it); latch.countDown() },
        )
        server.start()
        assertTrue("el servidor no arrancó en 5 s", latch.await(5, TimeUnit.SECONDS))
        return server to baseUrl.get()
    }

    private fun get(baseUrl: String, path: String): HttpResponse {
        val target = URL(baseUrl + path)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), 3_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: ${target.host}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.getOutputStream().flush()
            return readResponse(socket.getInputStream())
        }
    }

    private fun post(baseUrl: String, path: String, contentType: String, body: String): HttpResponse {
        val target = URL(baseUrl + path)
        val bytes = body.toByteArray()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), 3_000)
            socket.soTimeout = 3_000
            val head = "POST $path HTTP/1.1\r\n" +
                "Host: ${target.host}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out: OutputStream = socket.getOutputStream()
            out.write(head.toByteArray())
            out.write(bytes)
            out.flush()
            return readResponse(socket.getInputStream())
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun readResponse(input: java.io.InputStream): HttpResponse {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val status = reader.readLine().orEmpty()
        var length = 0
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            line = reader.readLine()
        }
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return HttpResponse(
            code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0,
            body = String(buffer, 0, read),
        )
    }

    @Test
    fun `arranca en un puerto de cinco cifras y publica la url local`() {
        val (server, url) = startServer()
        try {
            assertTrue("URL inesperada: $url", url.startsWith("http://"))
            val port = url.substringAfterLast(':').toInt()
            assertTrue("puerto fuera de rango: $port", port in 10000..59999)
            assertEquals(url, server.url)
            assertTrue(server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `el formulario del movil pide url y pin`() {
        val (server, url) = startServer()
        try {
            val response = get(url, "/")
            assertEquals(200, response.code)
            assertTrue(response.body.contains("Vincular con la TV"))
            assertTrue(response.body.contains("type=\"text\""))
            assertTrue(response.body.contains("type=\"password\""))
            assertTrue(response.body.contains("maxlength=\"6\""))
            assertTrue(response.body.contains("/submit"))
            assertTrue(response.body.contains("Datos enviados a la TV"))
            assertTrue(response.body.contains("fetch('/submit'"))
            assertTrue(response.body.contains("application/json"))
            // La página se sirve tal cual: sin plantillas Kotlin sin resolver.
            assertFalse(response.body.contains("\${"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `el post json entrega url y pin y apaga el servidor`() {
        val received = AtomicReference<Pair<String, String>>()
        val done = CountDownLatch(1)
        val latch = CountDownLatch(1)
        val url = AtomicReference<String>()
        val server = LocalPairingServer(
            onCredentials = { u, p -> received.set(u to p); done.countDown() },
            onReady = { url.set(it); latch.countDown() },
        )
        server.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        try {
            val response = post(
                url.get(),
                "/submit",
                "application/json",
                """{"url":"https://mi-servidor.com","pin":"123456"}""",
            )
            assertEquals(200, response.code)
            assertTrue(response.body.contains("\"ok\":true"))
            assertTrue("no llegó el aviso a la TV", done.await(5, TimeUnit.SECONDS))
            assertEquals("https://mi-servidor.com" to "123456", received.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `el post urlencoded tambien vale`() {
        val received = AtomicReference<Pair<String, String>>()
        val latch = CountDownLatch(1)
        val url = AtomicReference<String>()
        val server = LocalPairingServer(
            onCredentials = { u, p -> received.set(u to p); latch.countDown() },
            onReady = { url.set(it) },
        )
        server.start()
        Thread.sleep(600)
        try {
            val response = post(
                url.get() ?: error("sin URL"),
                "/submit",
                "application/x-www-form-urlencoded",
                "url=https%3A%2F%2Fmi-servidor.com&pin=654321",
            )
            assertEquals(200, response.code)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("https://mi-servidor.com" to "654321", received.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `un pin corto se rechaza sin avisar a la tv`() {
        val called = AtomicReference(false)
        val latch = CountDownLatch(1)
        val url = AtomicReference<String>()
        val server = LocalPairingServer(
            onCredentials = { _, _ -> called.set(true) },
            onReady = { url.set(it); latch.countDown() },
        )
        server.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        try {
            val response = post(url.get(), "/submit", "application/json", """{"url":"https://a.com","pin":"12"}""")
            assertEquals(400, response.code)
            assertFalse(called.get())
            assertTrue(server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop cierra el puerto y se puede llamar dos veces`() {
        val (server, url) = startServer()
        val port = url.substringAfterLast(':').toInt()
        server.stop()
        server.stop()
        assertFalse(server.isRunning)
        var failed = false
        try {
            Socket().use { it.connect(InetSocketAddress(URL(url).host, port), 500) }
        } catch (expected: Exception) {
            failed = true
        }
        assertTrue("el puerto $port seguía abierto tras stop()", failed)
    }
}
