package com.mantra.mapserver

import android.content.Context
import android.graphics.Bitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ONE MAPSFORGE FILE, TURNED INTO ORDINARY PNG TILES.
 *
 * This is the same call sequence that was run on a desk on 15.9.2026 to prove mapsforge renders
 * the Croatia file at every zoom to 21 — tools/RenderProbe.java in MANTRA_TRAIL. It is the whole
 * reason this app exists: the rendering works, so put it where it can be done once, kept as a
 * PNG, and handed to anything that asks over HTTP.
 *
 * RENDERED TILES ARE KEPT ON DISK FOREVER. A tile of a map file only changes when the file is
 * replaced, so the second visit to a zoom is a file read. That is what makes the map instant, and
 * it costs about fifteen kilobytes a tile.
 */
class Renderer(context: Context, val name: String, val file: File) {

    private val displayModel = DisplayModel().apply { setFixedTileSize(TILE) }
    private val factory = AndroidGraphicFactory.INSTANCE
    private val mapFile = MapFile(file)
    private val theme = RenderThemeFuture(factory, MapsforgeThemes.DEFAULT, displayModel).also {
        Thread(it).start()
    }
    private val renderer = DatabaseRenderer(
        mapFile,
        factory,
        InMemoryTileCache(32),
        null,
        true,
        true,
        null,
    )

    private val cacheDir = File(File(context.filesDir, "tiles"), name).apply { mkdirs() }

    val boundingBox: String
        get() = mapFile.mapFileInfo.boundingBox.let {
            "${it.minLatitude}, ${it.minLongitude} to ${it.maxLatitude}, ${it.maxLongitude}"
        }

    val fileBytes: Long get() = file.length()

    /** Bytes of a PNG for this tile, from the cache if it is there, or rendered and then kept. */
    @Synchronized
    fun tile(zoom: Int, x: Int, y: Int): ByteArray? {
        val cached = File(cacheDir, "$zoom-$x-$y.png")
        if (cached.exists() && cached.length() > 0) return cached.readBytes()
        return try {
            val tile = Tile(x, y, zoom.toByte(), TILE)
            val job = RendererJob(tile, mapFile, theme, displayModel, 1f, false, false)
            val bitmap = renderer.executeJob(job) ?: return null
            val android = AndroidGraphicFactory.getBitmap(bitmap)
            val out = ByteArrayOutputStream(24 * 1024)
            android.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.decrementRefCount()
            val bytes = out.toByteArray()
            // Written beside itself and moved into place, so a tile half-written when the phone
            // is put to sleep is never read back as a tile.
            val part = File(cacheDir, "$zoom-$x-$y.part")
            part.writeBytes(bytes)
            part.renameTo(cached)
            bytes
        } catch (e: Exception) {
            null
        }
    }

    /** How much room this map's rendered tiles are taking. */
    fun cacheBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(): Int {
        val files = cacheDir.listFiles() ?: return 0
        var gone = 0
        files.forEach { if (it.delete()) gone++ }
        return gone
    }

    fun close() {
        theme.decrementRefCount()
        mapFile.close()
    }

    companion object {
        const val TILE = 256
    }
}
