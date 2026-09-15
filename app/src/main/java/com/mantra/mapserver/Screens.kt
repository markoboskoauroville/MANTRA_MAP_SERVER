package com.mantra.mapserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * ONE SCREEN, AND IT ANSWERS THE FOUR QUESTIONS SOMEBODY HAS ABOUT A SERVER: is it listening,
 * what address, what is it serving, and who has been asking.
 *
 * The AGY look of every app in this account (design-language.md 3), and the same rules: nothing
 * appears or disappears, colour carries state, the way out sits at the right-hand end of its row,
 * and every screen scrolls because the list on this one grows with the network.
 */
private val GAP = 10.dp

object Tick {
    var n by mutableIntStateOf(0)
    fun bump() {
        n += 1
    }
}

@Composable
fun ServerApp(
    version: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddFile: () -> Unit,
    onDownload: (Regions.Region) -> Unit,
    onRemove: (String) -> Unit,
    onCopyTemplate: (String) -> Unit,
    onClearTiles: () -> Unit,
    onForgetCallers: () -> Unit,
) {
    val listening by Running.listening.collectAsState()
    val maps by Maps.names.collectAsState()
    val note by Maps.note.collectAsState()
    var callers by remember { mutableIntStateOf(0) }
    var tiles by remember { mutableIntStateOf(0) }
    var bytes by remember { mutableIntStateOf(0) }

    // The counters are read rather than pushed: the server counts on its own threads, and a
    // screen that watches is simpler than a server that notifies. Bounded by the composition.
    LaunchedEffect(Unit) {
        while (true) {
            Running.refresh()
            callers = Usage.callers().size
            tiles = Usage.totalTiles()
            bytes = (Usage.totalBytes() / 1_000).toInt()
            delay(1_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("mantra map server", Paint.Dim, 13, TextAlign.Start)
                Label(
                    text = if (listening) "listening" else "stopped",
                    colour = if (listening) Paint.Green else Paint.Dim,
                    size = 13,
                )
            }

            // THE ONE BUTTON THAT MATTERS, and it says what the next press will do.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (listening) Paint.Red else Paint.Amber)
                    .clickable { if (listening) onStop() else onStart() },
                contentAlignment = Alignment.Center,
            ) {
                Label(if (listening) "stop serving" else "start serving", Paint.Ground, 16)
            }

            val address = remember(Tick.n, listening) { Server.localAddress() }
            Panel {
                Label("this phone   http://127.0.0.1:${Running.port}", Paint.Sand, 12, TextAlign.Start)
                Label(
                    text = address?.let { "the network  http://$it:${Running.port}" }
                        ?: "the network  no wifi address just now",
                    colour = if (address != null) Paint.Sand else Paint.Dim,
                    size = 12,
                    align = TextAlign.Start,
                )
                Label("$tiles tiles served · $bytes kB · $callers callers", Paint.Dim, 11, TextAlign.Start)
            }

            if (note != null) {
                Panel { Label(note ?: "", Paint.Amber, 12, TextAlign.Start) }
            }

            Label("maps", Paint.Dim, 12, TextAlign.Start)
            if (maps.isEmpty()) {
                Label("None yet. Download one below, or pick a .map file.", Paint.Dim, 12, TextAlign.Start)
            }
            maps.forEach { name ->
                Panel {
                    Label(name, Paint.Sand, 14, TextAlign.Start)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label(
                            text = "copy URL",
                            colour = Paint.Amber,
                            size = 12,
                            modifier = Modifier.clickable { onCopyTemplate(name) },
                        )
                        Label(
                            text = "remove",
                            colour = Paint.Red,
                            size = 12,
                            modifier = Modifier.clickable { onRemove(name) },
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Veil)
                    .clickable(onClick = onAddFile)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("add a .map file from the phone", Paint.Sand, 12, TextAlign.Start)
                Label("picker", Paint.Amber, 11)
            }

            Label("download a region from mapsforge", Paint.Dim, 12, TextAlign.Start)
            Regions.ALL.forEach { region ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .clickable { onDownload(region) }
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(region.label, Paint.Sand, 12, TextAlign.Start)
                    Label(Regions.sizeLabel(region), Paint.Amber, 11)
                }
            }

            Label("who has been asking", Paint.Dim, 12, TextAlign.Start)
            val rows = remember(Tick.n, tiles) { Usage.callers() }
            if (rows.isEmpty()) {
                Label("Nobody yet.", Paint.Dim, 12, TextAlign.Start)
            }
            rows.forEach { caller ->
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label(Usage.label(caller.address), Paint.Sand, 12, TextAlign.Start)
                        Label("${caller.tiles} tiles", Paint.Sand, 12)
                        Label(Usage.formatBytes(caller.bytes), Paint.Dim, 11)
                        Label(
                            text = if (caller.misses > 0) "${caller.misses} refused" else " ",
                            colour = Paint.Amber,
                            size = 11,
                        )
                    }
                }
            }

            SettingRow("forget the caller list", "clear", onForgetCallers)
            SettingRow("delete every rendered tile", Usage.formatBytes(Maps.totalTileCacheBytes()), onClearTiles)
            Label("Mantra Map Server v$version", Paint.Dim, 10)
        }
    }
}

@Composable
private fun SettingRow(title: String, state: String, onPress: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Paint.Veil)
            .clickable(onClick = onPress)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, Paint.Sand, 12, TextAlign.Start)
        Label(state, Paint.Amber, 11)
    }
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Paint.Veil)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun Label(
    text: String,
    colour: Color,
    size: Int = 13,
    align: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = text,
        color = colour,
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
        maxLines = 2,
    )
}
