package com.mantra.mapserver

import java.util.Locale

/**
 * THE KEYS THIS SERVER ISSUES, AND WHO MAY ASK WITHOUT ONE. No Android imports (android-app.md 1).
 *
 * The server answers on the whole local network on purpose — that is how a laptop gets the maps —
 * and that is also how a café's wifi gets them. So it issues keys: the phone itself never needs
 * one, because a request from the loopback address came from this phone and nothing else can
 * forge that; everything arriving over the network must carry one.
 *
 * A KEY IS GENERATED HERE, SHOWN, AND KEPT. It is not a password to anything but a map, so it is
 * kept in full rather than hashed: he has to be able to read it off the screen to paste it into
 * another device, and a key he cannot read is a key he cannot use. What matters is that revoking
 * one takes effect at once, and that the table says which key each caller used.
 *
 * THE FORMAT IS MADE TO BE RECOGNISABLE, so the key parser in MANTRA_TRAIL and the scanner in the
 * build gate can both tell what it is: mms_ and thirty-two lowercase hexadecimal characters.
 */
object Access {

    const val PREFIX = "mms_"
    const val BODY_LENGTH = 32

    data class Key(
        val label: String,
        val value: String,
        val createdMs: Long,
        val uses: Int = 0,
        val lastUsedMs: Long = 0L,
    )

    /**
     * A key from random bytes the caller supplies. The randomness is the Android side's business
     * (SecureRandom); the shape is this side's, so the shape is what Test 1 can hold to account.
     */
    fun make(label: String, random: ByteArray, whenMs: Long): Key {
        require(random.size >= BODY_LENGTH / 2) { "a key needs at least ${BODY_LENGTH / 2} bytes" }
        val body = random.take(BODY_LENGTH / 2)
            .joinToString("") { String.format(Locale.US, "%02x", it) }
        return Key(labelOf(label), PREFIX + body, whenMs)
    }

    /** A label somebody typed, made printable. An unnamed key is still a key. */
    fun labelOf(raw: String): String {
        val cleaned = raw.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        return cleaned.take(40).ifBlank { "unnamed" }
    }

    /** Whether a string is the right shape to be one of ours. Shape only; not whether it is live. */
    fun looksLikeKey(text: String): Boolean =
        text.length == PREFIX.length + BODY_LENGTH &&
            text.startsWith(PREFIX) &&
            text.drop(PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Whether this address may be answered. The loopback addresses are this phone talking to
     * itself; everything else needs a key that is in the list.
     */
    fun mayServe(address: String, presented: String?, keys: List<Key>, requireFromNetwork: Boolean): Boolean {
        if (isLoopback(address)) return true
        if (!requireFromNetwork) return true
        val key = presented ?: return false
        return keys.any { it.value == key }
    }

    fun isLoopback(address: String): Boolean =
        address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1" ||
            address == "localhost" || address.startsWith("127.")

    /** What to say to a caller that has no key, in words that name the fix. */
    const val REFUSAL = "this server needs a key: add ?key=… to the URL, or make one in the app"

    /**
     * The parameters of a request, from the part after the question mark. A malformed pair is
     * skipped rather than throwing: this runs on whatever the network sends.
     */
    fun query(path: String): Map<String, String> {
        val after = path.substringAfter('?', "")
        if (after.isEmpty()) return emptyMap()
        val found = LinkedHashMap<String, String>()
        after.split('&').forEach { pair ->
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name.isNotEmpty() && value.isNotEmpty()) found[name] = decode(value)
        }
        return found
    }

    /** Percent-decoding, enough for a key and a name. An invalid escape is left as it was. */
    fun decode(text: String): String {
        if (!text.contains('%') && !text.contains('+')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '+' -> {
                    out.append(' ')
                    i++
                }

                c == '%' && i + 2 < text.length -> {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) {
                        out.append(c)
                        i++
                    } else {
                        out.append(hex.toChar())
                        i += 3
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The URL to hand another device: the template with the key already in it. */
    fun templateFor(host: String, port: Int, map: String, key: Key?): String {
        val base = Http.tileTemplate(host, port, map)
        return if (key == null) base else "$base?key=${key.value}"
    }

    /** What may be shown of a key in a list: enough to tell two apart, not the key itself. */
    fun mask(key: Key): String = PREFIX + "…" + key.value.takeLast(4)
}
