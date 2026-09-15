package com.mantra.mapserver

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * THE SERVER ITSELF: a ServerSocket, a thread pool, and nothing else.
 *
 * No web framework. A tile server answers four routes and the whole of its parsing is in Http.kt
 * where Test 1 can attack it; adding a library for that would be adding somebody else's release
 * schedule and somebody else's bugs to an app whose entire job is to be dependable while there is
 * no signal.
 *
 * IT LISTENS ON EVERY ADDRESS, ON PURPOSE. Mantra Trail reaches it on 127.0.0.1 without touching
 * the network at all; a laptop on the same wifi reaches it on the phone's address. That is the
 * feature, and it is also the risk, which is why every caller is counted by address and shown in
 * the app (Usage.kt) rather than logged where nobody looks.
 *
 * READING A REQUEST IS BOUNDED. One line, capped; the rest of the headers skipped without being
 * kept. A connection that opens and says nothing is closed by the socket's own timeout rather
 * than holding a thread for ever.
 */
class Server(
    private val port: Int,
    private val maps: () -> Map<String, Renderer>,
) {

    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)
    private var startedMs = 0L

    val isRunning: Boolean get() = running.get()

    val uptimeMs: Long get() = if (startedMs == 0L) 0L else System.currentTimeMillis() - startedMs

    /** Returns null when it is listening, or the reason it is not. */
    fun start(): String? {
        if (running.get()) return null
        return try {
            val server = ServerSocket(port)
            server.reuseAddress = true
            socket = server
            startedMs = System.currentTimeMillis()
            running.set(true)
            Thread({ accept(server) }, "map-server-accept").start()
            null
        } catch (e: Exception) {
            running.set(false)
            "Could not listen on port $port: ${e.javaClass.simpleName}"
        }
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (e: Exception) {
            // Closing a socket that is already closed is not news.
        }
        socket = null
        startedMs = 0L
    }

    private fun accept(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running.get()) continue else break
            }
            workers.execute { answer(client) }
        }
    }

    private fun answer(client: Socket) {
        val address = client.inetAddress?.hostAddress ?: ""
        try {
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()), 4096)
            val line = reader.readLine() ?: return
            val out = client.getOutputStream()
            when (val route = Http.route(line)) {
                is Http.Route.Tile -> serveTile(out, route.request, address)
                is Http.Route.Maps -> text(out, 200, "application/json", mapsJson())
                is Http.Route.Status -> text(
                    out,
                    200,
                    "application/json",
                    Usage.statusJson(uptimeMs, maps().keys.sorted(), port),
                )
                is Http.Route.Index -> text(out, 200, "text/html; charset=utf-8", indexPage())
                is Http.Route.Refused -> {
                    Usage.refused(address, System.currentTimeMillis())
                    text(out, route.code, "text/plain; charset=utf-8", route.why + "\n")
                }
            }
            out.flush()
        } catch (e: Exception) {
            // One bad connection is one bad connection. It never takes the server with it.
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // Nothing to do about a socket that will not close.
            }
        }
    }

    private fun serveTile(out: OutputStream, request: Http.TileRequest, address: String) {
        val renderer = maps()[request.map]
        if (renderer == null) {
            Usage.refused(address, System.currentTimeMillis())
            text(out, 404, "text/plain; charset=utf-8", "no map called ${request.map}\n")
            return
        }
        val bytes = renderer.tile(request.zoom, request.x, request.y)
        if (bytes == null) {
            // NOT AN ERROR AND NOT A PICTURE. A tile outside the file's own area has nothing to
            // draw, and 204 says exactly that: the request was fine, there is nothing here.
            Usage.refused(address, System.currentTimeMillis())
            out.write(Http.header(204, "image/png", 0).toByteArray())
            return
        }
        out.write(Http.header(200, "image/png", bytes.size, cacheSeconds = 30 * 24 * 3600).toByteArray())
        out.write(bytes)
        Usage.served(address, bytes.size, System.currentTimeMillis())
    }

    private fun text(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(Http.header(code, type, bytes.size).toByteArray())
        out.write(bytes)
    }

    private fun mapsJson(): String = buildString {
        append("{\n  \"maps\": [\n")
        maps().entries.sortedBy { it.key }.forEachIndexed { i, (name, renderer) ->
            if (i > 0) append(",\n")
            append("    {\"name\": \"").append(Usage.escape(name))
            append("\", \"template\": \"").append(Http.tileTemplate("127.0.0.1", port, name))
            append("\", \"fileBytes\": ").append(renderer.fileBytes)
            append(", \"area\": \"").append(Usage.escape(renderer.boundingBox)).append("\"}")
        }
        append("\n  ]\n}\n")
    }

    /** A page for a browser on the laptop, so the server can be checked from another machine. */
    private fun indexPage(): String = buildString {
        append("<!doctype html><meta charset=utf-8><title>Mantra Map Server</title>")
        append("<body style=\"background:#0B0D10;color:#F2DDB4;font-family:monospace;padding:2rem\">")
        append("<h1 style=\"color:#E8A64B\">Mantra Map Server</h1>")
        append("<p>Serving ").append(maps().size).append(" map(s), ")
        append(Usage.totalTiles()).append(" tiles since it started.</p><ul>")
        maps().keys.sorted().forEach {
            append("<li>").append(it).append(" — <code>")
            append(Http.tileTemplate("127.0.0.1", port, it)).append("</code></li>")
        }
        append("</ul><p><a style=\"color:#E8A64B\" href=\"/status\">status</a> · ")
        append("<a style=\"color:#E8A64B\" href=\"/maps\">maps</a></p></body>")
    }

    companion object {
        const val DEFAULT_PORT = 8088

        /**
         * The address another device on the same network would use. Loopback and the link-local
         * addresses are skipped, because neither is reachable from a laptop.
         */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address: InetAddress ->
                    !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.hostAddress?.contains(':') == false
                }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
