package com.mantra.mapserver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * THE KEYS, KEPT BETWEEN SESSIONS.
 *
 * SecureRandom, not Random: a key anybody can predict from the time it was made is not a key.
 * That is the only reason this file is on the Android side at all — the shape, the door and the
 * URL are all in Access.kt where Test 1 attacks them.
 */
object KeyStore {

    private const val FILE = "mantra-map-server"
    private const val KEY_LIST = "keys"
    private const val KEY_REQUIRE = "requireKey"

    private val random = SecureRandom()

    private val _keys = MutableStateFlow<List<Access.Key>>(emptyList())
    val keys: StateFlow<List<Access.Key>> = _keys.asStateFlow()

    private val _requireFromNetwork = MutableStateFlow(true)
    val requireFromNetwork: StateFlow<Boolean> = _requireFromNetwork.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val text = prefs(context).getString(KEY_LIST, "") ?: ""
        _keys.value = text.lines().mapNotNull { line ->
            val bits = line.split('\t')
            if (bits.size < 3) return@mapNotNull null
            val value = bits[1]
            if (!Access.looksLikeKey(value)) return@mapNotNull null
            Access.Key(bits[0], value, bits[2].toLongOrNull() ?: 0L, bits.getOrNull(3)?.toIntOrNull() ?: 0)
        }
        _requireFromNetwork.value = prefs(context).getBoolean(KEY_REQUIRE, true)
    }

    private fun save(context: Context) {
        val text = _keys.value.joinToString("\n") { "${it.label}\t${it.value}\t${it.createdMs}\t${it.uses}" }
        prefs(context).edit().putString(KEY_LIST, text).apply()
    }

    fun make(context: Context, label: String): Access.Key {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Access.make(label, bytes, System.currentTimeMillis())
        _keys.value = _keys.value + key
        save(context)
        return key
    }

    fun revoke(context: Context, value: String) {
        _keys.value = _keys.value.filterNot { it.value == value }
        save(context)
    }

    /** Counted in memory only: a use is interesting today and not worth a disk write each time. */
    fun used(value: String) {
        _keys.value = _keys.value.map {
            if (it.value == value) it.copy(uses = it.uses + 1, lastUsedMs = System.currentTimeMillis()) else it
        }
    }

    fun setRequireFromNetwork(context: Context, value: Boolean) {
        _requireFromNetwork.value = value
        prefs(context).edit().putBoolean(KEY_REQUIRE, value).apply()
    }

    fun current(): List<Access.Key> = _keys.value

    fun required(): Boolean = _requireFromNetwork.value
}
