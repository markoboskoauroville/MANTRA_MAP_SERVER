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
        KeyStore.load(this)

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
                onMakeKey = {
                    val key = KeyStore.make(this, "key ${KeyStore.current().size + 1}")
                    copy(key.value)
                    Maps.say("New key made and copied. It is on the clipboard now, and in the list.")
                    Tick.bump()
                },
                onRevoke = { key ->
                    KeyStore.revoke(this, key.value)
                    Maps.say("Revoked ${key.label}. Anything using it stops now.")
                    Tick.bump()
                },
                onCopyKeyed = { key ->
                    val host = Server.localAddress() ?: "127.0.0.1"
                    val map = Maps.names.value.firstOrNull() ?: "croatia"
                    copy(Access.templateFor(host, Running.port, map, key))
                    Maps.say("URL copied, with the key in it")
                },
                onToggleRequire = {
                    val next = !KeyStore.required()
                    KeyStore.setRequireFromNetwork(this, next)
                    Maps.say(
                        if (next) "The network must present a key now"
                        else "Open to anything on this wifi. The caller list is how you see who."
                    )
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
        copy(template)
        Maps.say("Copied: $template")
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("mantra map server", text))
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
