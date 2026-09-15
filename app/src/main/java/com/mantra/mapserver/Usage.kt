package com.mantra.mapserver

import java.util.Locale

/**
 * WHO IS ASKING, AND FOR HOW MUCH. No Android imports (android-app.md 1).
 *
 * Baba, 15.9.2026: *"it needs to log usage, how many tiles are served from which IP address."*
 *
 * A server on a phone on a shared network is a thing other people can use without asking, so the
 * only honest version of it shows every address that has asked and what it took. Not a debug log
 * that has to be enabled: a page in the app, on by default, that says one device has taken four
 * hundred tiles today and which one.
 *
 * The counting is in memory and bounded. A network with a hundred devices on it would otherwise
 * grow a hundred rows of dust; past the cap the smallest are folded into "others" rather than
 * letting the list become the reason the app is slow.
 */
object Usage {

    /** How many addresses are kept apart before the rest become one row. */
    const val MAX_CALLERS = 32

    /** The one row every straggler goes into. Fixed, so a second fold cannot overwrite a first. */
    const val OTHERS = "others"

    data class Caller(
        val address: String,
        val tiles: Int,
        val bytes: Long,
        val misses: Int,
        val firstMs: Long,
        val lastMs: Long,
    )

    private val callers = LinkedHashMap<String, Caller>()

    @Synchronized
    fun served(address: String, bytes: Int, whenMs: Long) = record(address, bytes, whenMs, hit = true)

    @Synchronized
    fun refused(address: String, whenMs: Long) = record(address, 0, whenMs, hit = false)

    private fun record(address: String, bytes: Int, whenMs: Long, hit: Boolean) {
        val key = if (address.isBlank()) "unknown" else address
        val existing = callers[key]
        callers[key] = if (existing == null) {
            Caller(key, if (hit) 1 else 0, bytes.toLong(), if (hit) 0 else 1, whenMs, whenMs)
        } else {
            existing.copy(
                tiles = existing.tiles + if (hit) 1 else 0,
                bytes = existing.bytes + bytes,
                misses = existing.misses + if (hit) 0 else 1,
                lastMs = whenMs,
            )
        }
        if (callers.size > MAX_CALLERS) fold()
    }

    /**
     * The busiest stay named; the rest become ONE row, and it is always the same row.
     *
     * Test 1 caught this: the folded row used to be called "others (2)", with the count in its
     * name, so the next fold that produced two stragglers wrote over the previous one and its
     * tiles vanished. Ninety-six tiles went in and thirty-two came out. The key is now fixed and
     * the new stragglers are ADDED to whatever it already holds.
     */
    private fun fold() {
        val sorted = callers.values.sortedByDescending { it.tiles }
        val keep = sorted.take(MAX_CALLERS - 1)
        val rest = sorted.drop(MAX_CALLERS - 1)
        if (rest.isEmpty()) return
        val folded = Caller(
            address = OTHERS,
            tiles = rest.sumOf { it.tiles },
            bytes = rest.sumOf { it.bytes },
            misses = rest.sumOf { it.misses },
            firstMs = rest.minOf { it.firstMs },
            lastMs = rest.maxOf { it.lastMs },
        )
        callers.clear()
        keep.forEach { callers[it.address] = it }
        val existing = callers[OTHERS]
        callers[OTHERS] = if (existing == null) {
            folded
        } else {
            existing.copy(
                tiles = existing.tiles + folded.tiles,
                bytes = existing.bytes + folded.bytes,
                misses = existing.misses + folded.misses,
                firstMs = minOf(existing.firstMs, folded.firstMs),
                lastMs = maxOf(existing.lastMs, folded.lastMs),
            )
        }
    }

    /** Busiest first, which is the order somebody reads a list like this in. */
    @Synchronized
    fun callers(): List<Caller> = callers.values.sortedByDescending { it.tiles }

    @Synchronized
    fun totalTiles(): Int = callers.values.sumOf { it.tiles }

    @Synchronized
    fun totalBytes(): Long = callers.values.sumOf { it.bytes }

    @Synchronized
    fun forget() = callers.clear()

    /**
     * An address as it should be shown. IPv6 loopback and IPv4 loopback are the same thing to a
     * person, and "this phone" is what they mean.
     */
    fun label(address: String): String = when (address) {
        "127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost" -> "this phone"
        else -> address
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} kB"
        else -> "$bytes B"
    }

    /** The whole table as JSON, for /status, built without a library. */
    fun statusJson(uptimeMs: Long, maps: List<String>, port: Int): String = buildString {
        append("{\n")
        append("  \"uptimeSeconds\": ").append(uptimeMs / 1000).append(",\n")
        append("  \"port\": ").append(port).append(",\n")
        append("  \"tilesServed\": ").append(totalTiles()).append(",\n")
        append("  \"bytesServed\": ").append(totalBytes()).append(",\n")
        append("  \"maps\": [").append(maps.joinToString(", ") { "\"${escape(it)}\"" }).append("],\n")
        append("  \"callers\": [\n")
        callers().forEachIndexed { i, c ->
            if (i > 0) append(",\n")
            append("    {\"address\": \"").append(escape(c.address))
            append("\", \"tiles\": ").append(c.tiles)
            append(", \"bytes\": ").append(c.bytes)
            append(", \"refused\": ").append(c.misses)
            append(", \"lastMs\": ").append(c.lastMs).append("}")
        }
        append("\n  ]\n}\n")
    }

    /** The four characters that would otherwise make a JSON file unreadable. */
    fun escape(text: String): String = buildString {
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> if (c.code >= 0x20) append(c)
        }
    }
}
