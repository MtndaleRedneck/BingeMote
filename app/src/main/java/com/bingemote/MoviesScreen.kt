package com.bingemote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Movies folder on the PC: highlight a file with the D-pad (or volume keys), OK plays it. */
@Composable
fun MoviesScreen(remote: RemoteState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(remote.store.moviesPath) }
    var items by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()

    fun load(dir: String) {
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    Vlc.browse(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, dir)
                }
                val up = raw.filter { it.name == ".." }
                val dirs = raw.filter { it.isDir && it.name != ".." }
                val files = raw.filter { !it.isDir && isVideoFile(it.name) }
                items = up + dirs + files
                selected = if (up.isNotEmpty()) 1.coerceAtMost(items.size - 1) else 0
                path = dir
                loading = false
                scroll.scrollTo(0)
            } catch (e: Exception) {
                loading = false
                error = "Couldn't open that folder. Check the Movies folder in Settings, and that the PC is on."
            }
        }
    }

    fun open(item: BrowseItem) {
        when {
            item.isDir -> load(item.path)
            else -> {
                scope.launch(Dispatchers.IO) {
                    try { Vlc.playUri(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, item.uri) } catch (e: Exception) { }
                }
                onBack()
            }
        }
    }

    LaunchedEffect(Unit) { load(path) }

    // Volume keys steer the highlight too (wired from MainActivity).
    MoviesKeyHandler.register { key ->
        when (key) {
            MoviesKeyHandler.Key.UP -> if (selected > 0) selected--
            MoviesKeyHandler.Key.DOWN -> if (selected < items.size - 1) selected++
        }
    }
    DisposableEffect(Unit) { onDispose { MoviesKeyHandler.register(null) } }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Movies", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            prettyPath(path),
            color = Muted, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(scroll),
        ) {
            when {
                loading -> Text("Loading...", color = Muted, modifier = Modifier.padding(8.dp))
                error != null -> Text(error!!, color = ErrorRed, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                items.isEmpty() -> Text("No videos in this folder.", color = Muted, modifier = Modifier.padding(8.dp))
                else -> items.forEachIndexed { i, item ->
                    val isSel = i == selected
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSel) OrangeGradient else CardGradient)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                    ) {
                        Column {
                            Text(
                                if (item.name == "..") "Up one folder" else item.name,
                                color = if (isSel) androidx.compose.ui.graphics.Color(0xFF1B1B1E) else TextC,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (!item.isDir && item.sizeBytes > 0) {
                                Text(
                                    formatSize(item.sizeBytes),
                                    color = if (isSel) androidx.compose.ui.graphics.Color(0xFF3D2A12) else Muted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // D-pad
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PressTile(onTap = { if (selected > 0) selected-- }, modifier = Modifier.size(64.dp, 52.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = Orange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PressTile(onTap = { items.getOrNull(0)?.takeIf { it.name == ".." }?.let { load(it.path) } }, modifier = Modifier.size(64.dp, 52.dp)) {
                    Icon(Icons.Default.KeyboardArrowLeft, null, tint = Orange)
                }
                Spacer(Modifier.width(10.dp))
                PressTile(onTap = { items.getOrNull(selected)?.let { open(it) } }, background = OrangeGradient, modifier = Modifier.size(72.dp, 52.dp)) {
                    Text("OK", color = androidx.compose.ui.graphics.Color(0xFF1B1B1E), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                PressTile(onTap = { items.getOrNull(selected)?.takeIf { it.isDir && it.name != ".." }?.let { load(it.path) } }, modifier = Modifier.size(64.dp, 52.dp)) {
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = Orange)
                }
            }
            PressTile(onTap = { if (selected < items.size - 1) selected++ }, modifier = Modifier.size(64.dp, 52.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = Orange)
            }
        }
        Text(
            "Arrows move the highlight, OK plays. Volume keys work as arrows too.",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
    }
}

/** Tiny hook so MainActivity's volume keys can steer the list. */
object MoviesKeyHandler {
    enum class Key { UP, DOWN }
    private var handler: ((Key) -> Unit)? = null
    fun register(h: ((Key) -> Unit)?) { handler = h }
    fun onKey(key: Key) { handler?.invoke(key) }
}

/** "C:\Users\you\Desktop\Movies" -> "Desktop > Movies" for the path line. */
fun prettyPath(path: String): String {
    val parts = path.replace('/', '\\').split('\\').filter { it.isNotBlank() && !it.endsWith(":") }
    return parts.takeLast(2).joinToString(" > ").ifBlank { path }
}
