package com.mantra.mapserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

/**
 * ONE SCREEN: is it serving, on what address, which maps, and who has been asking.
 */
class MainActivity : ComponentActivity() {

    private var downloading = false

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Serving works either way; without it the ongoing notice cannot be shown. */ }

    private val pickMapFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "map.map"
        Maps.say("Copying $name in…")
        lifecycleScope.launch {
            val problem = Maps.add(this@MainActivity, uri, name)
            Maps.say(problem ?: "Added ${name.substringBeforeLast('.')}")
            Tick.bump()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AndroidGraphicFactory.createInstance(application)
        Maps.load(this)

        setContent {
            ServerApp(
                version = BuildConfig.VERSION_NAME,
                onStart = { ServerService.start(this) },
                onStop = { ServerService.stop(this) },
                onAddFile = { pickMapFile.launch(arrayOf("*/*")) },
                onDownload = ::download,
                onRemove = { name ->
                    Maps.say(Maps.remove(this, name) ?: "Removed $name")
                    Tick.bump()
                },
                onCopyTemplate = ::copyTemplate,
                onClearTiles = {
                    val gone = Maps.clearAllTileCaches()
                    Maps.say("Cleared $gone rendered tiles")
                    Tick.bump()
                },
                onForgetCallers = {
                    Usage.forget()
                    Tick.bump()
                },
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        Running.refresh()
    }

    /** The URL a map app needs, on the clipboard, because nobody types one of those correctly. */
    private fun copyTemplate(name: String) {
        val template = Http.tileTemplate("127.0.0.1", Running.port, name)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("tile template", template))
        Maps.say("Copied: $template")
    }

    private fun download(region: Regions.Region) {
        if (downloading) {
            Maps.say("Already fetching a map")
            return
        }
        if (Download.isPresent(this, region)) {
            Maps.say("${region.label} is already here")
            return
        }
        downloading = true
        Maps.say("Fetching ${region.label}, ${Regions.sizeLabel(region)}")
        lifecycleScope.launch {
            val problem = Download.fetch(this@MainActivity, region) { p ->
                Maps.say("${region.label} ${p.percent}%, ${p.done / 1_000_000} of ${p.total / 1_000_000} MB")
            }
            downloading = false
            if (problem != null) {
                Maps.say(problem)
            } else {
                Maps.load(this@MainActivity)
                Maps.say("${region.label} is on the phone and being served")
            }
            Tick.bump()
        }
    }
}
