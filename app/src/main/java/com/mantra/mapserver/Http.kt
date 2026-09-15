package com.mantra.mapserver

/**
 * WHAT AN HTTP REQUEST MEANS, AS ARITHMETIC. No Android imports, no sockets, no threads
 * (android-app.md 1) — so the part of a server that is easiest to get wrong is the part Test 1
 * can attack on a desk in a tenth of a second.
 *
 * WHY A SERVER AT ALL. Baba, 15.9.2026: the offline map and Thunderforest should reach the app
 * the same way, through a URL. So this app renders mapsforge files into ordinary z/x/y tiles and
 * serves them on the phone — to Mantra Trail over the loopback address, and to anything else on
 * the same network. One code path in the map app instead of two, rendered tiles kept as PNGs, and
 * the rendering out of the way in its own process.
 *
 * THE PARSER IS THE FRONT DOOR AND IT FACES THE NETWORK. Everything it can be sent that is not a
 * tile request is a case here: a path that climbs out of the maps folder, a zoom that is not a
 * number, a tile number past the edge of the world, a request line with no method. It answers a
 * refusal rather than throwing, because a server that dies on a malformed line is a server that
 * anybody on the network can switch off.
 */
object Http {

    /** The address a tile request names. */
    data class TileRequest(val map: String, val zoom: Int, val x: Int, val y: Int)

    sealed interface Route {
        /** GET /tiles/{map}/{z}/{x}/{y}.png */
        data class Tile(val request: TileRequest, val query: Map<String, String> = emptyMap()) : Route

        /** GET /maps — what this server is holding. */
        data object Maps : Route

        /** GET /status — uptime, tiles served, who has been asking. */
        data object Status : Route

        /** GET / — a page a human can read in a browser. */
        data object Index : Route

        data class Refused(val code: Int, val why: String) : Route
    }

    /** The longest request line worth reading. Anything past this is not a tile request. */
    const val MAX_REQUEST_LINE = 2048

    /**
     * Parse a request line: "GET /tiles/croatia/12/2229/1460.png HTTP/1.1".
     *
     * Only GET and HEAD are answered. Everything else is refused with the code that says why,
     * rather than with silence.
     */
    fun route(requestLine: String): Route {
        if (requestLine.length > MAX_REQUEST_LINE) return Route.Refused(414, "request line too long")
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return Route.Refused(400, "not a request line")
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") return Route.Refused(405, "only GET and HEAD")
        val whole = parts[1]
        val path = whole.substringBefore('?')
        val query = Access.query(whole)
        return when {
            path == "/" || path == "/index.html" -> Route.Index
            path == "/maps" || path == "/maps.json" -> Route.Maps
            path == "/status" || path == "/status.json" -> Route.Status
            path.startsWith("/tiles/") -> tile(path, query)
            else -> Route.Refused(404, "no such thing here")
        }
    }

    private fun tile(path: String, query: Map<String, String>): Route {
        val bits = path.removePrefix("/tiles/").split('/')
        if (bits.size != 4) return Route.Refused(400, "expected /tiles/map/z/x/y.png")
        val map = safeName(bits[0]) ?: return Route.Refused(400, "that map name is not allowed")
        val zoom = bits[1].toIntOrNull() ?: return Route.Refused(400, "zoom is not a number")
        val x = bits[2].toIntOrNull() ?: return Route.Refused(400, "x is not a number")
        val yPart = bits[3].removeSuffix(".png").removeSuffix(".PNG")
        val y = yPart.toIntOrNull() ?: return Route.Refused(400, "y is not a number")
        if (zoom < 0 || zoom > 22) return Route.Refused(400, "zoom is outside 0 to 22")
        // A NEGATIVE TILE AND A TILE PAST THE EDGE ARE DIFFERENT THINGS, and the codes say which.
        // Negative is a malformed request — no map has ever had a tile -1. Past the edge is a
        // request that makes sense and simply names nothing, which is what 404 is for.
        if (x < 0 || y < 0) return Route.Refused(400, "a tile number cannot be negative")
        val edge = 1 shl zoom
        if (x >= edge || y >= edge) {
            return Route.Refused(404, "that tile is off the edge of zoom $zoom")
        }
        return Route.Tile(TileRequest(map, zoom, x, y), query)
    }

    /**
     * A map name from the network, made safe or refused. THE ONLY CHARACTERS ALLOWED ARE ONES
     * THAT CANNOT CLIMB: no slash, no backslash, no dot-dot, nothing but letters, digits, dash
     * and underscore. A server that will open ../../../../etc/passwd for anybody who asks is not
     * a server, and the phone it runs on is somebody's whole life.
     */
    fun safeName(raw: String): String? {
        if (raw.isEmpty() || raw.length > 64) return null
        if (raw.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
        return raw
    }

    fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        414 -> "URI Too Long"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }

    /**
     * The head of a response. Tiles are allowed to be cached hard by whoever asked: a rendered
     * tile of a map file that only changes when the file is replaced is as static as anything on
     * a network gets.
     */
    fun header(
        code: Int,
        contentType: String,
        contentLength: Int,
        cacheSeconds: Int = 0,
        extra: List<String> = emptyList(),
    ): String = buildString {
        append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Content-Length: ").append(contentLength).append("\r\n")
        if (cacheSeconds > 0) append("Cache-Control: public, max-age=").append(cacheSeconds).append("\r\n")
        append("Connection: close\r\n")
        // Anything on the network may ask, including a browser page from another device.
        append("Access-Control-Allow-Origin: *\r\n")
        extra.forEach { append(it).append("\r\n") }
        append("\r\n")
    }

    /** The URL template to give a map app, for a server at this address. */
    fun tileTemplate(host: String, port: Int, map: String): String =
        "http://$host:$port/tiles/$map/{z}/{x}/{y}.png"
}
