package com.mantra.mapserver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * THE MAPS THIS SERVER IS HOLDING, and where they came from.
 *
 * Two ways in, both his (15.9.2026): the app downloads a region from mapsforge's own server, or
 * he picks a .map file with the file picker and it is copied in. Copied, not referenced: a server
 * that answers a tile request by reaching through a document permission granted months ago is a
 * server that stops working for a reason nobody can see.
 *
 * The folder is the list, as in the track manager: no index to drift out of step with the files.
 */
object Maps {

    private val _maps = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _maps.asStateFlow()

    private val renderers = LinkedHashMap<String, Renderer>()

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    fun say(message: String?) {
        _note.value = message
    }

    fun folder(context: Context): File = File(context.filesDir, "maps").apply { mkdirs() }

    /** Open every .map file in the folder. Called once when the app starts. */
    @Synchronized
    fun load(context: Context) {
        renderers.values.forEach { it.close() }
        renderers.clear()
        folder(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".map", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val name = Http.safeName(file.nameWithoutExtension) ?: return@forEach
                try {
                    renderers[name] = Renderer(context, name, file)
                } catch (e: Exception) {
                    // A file that mapsforge will not open is named on the screen rather than
                    // crashing the app it was added to.
                    say("${file.name} could not be opened: ${e.javaClass.simpleName}")
                }
            }
        _maps.value = renderers.keys.toList()
    }

    @Synchronized
    fun current(): Map<String, Renderer> = renderers.toMap()

    @Synchronized
    fun renderer(name: String): Renderer? = renderers[name]

    /**
     * Copy a picked file in under a safe name. Returns null when it worked, or the reason.
     */
    fun add(context: Context, uri: Uri, suggestedName: String): String? {
        val name = Http.safeName(suggestedName.substringBeforeLast('.').replace(' ', '-'))
            ?: return "That file's name cannot be used. Letters, digits, dash and underscore only."
        val target = File(folder(context), "$name.map")
        if (target.exists()) return "There is already a map called $name"
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return "That file could not be read"
            load(context)
            null
        } catch (e: Exception) {
            target.delete()
            "The file could not be copied: ${e.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun remove(context: Context, name: String): String? {
        val renderer = renderers[name] ?: return "No map called $name"
        renderer.clearCache()
        renderer.close()
        val file = renderer.file
        renderers.remove(name)
        _maps.value = renderers.keys.toList()
        return if (file.delete()) null else "The file could not be deleted"
    }

    fun totalTileCacheBytes(): Long = renderers.values.sumOf { it.cacheBytes() }

    fun clearAllTileCaches(): Int = renderers.values.sumOf { it.clearCache() }
}
