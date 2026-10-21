package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * The movies folder picked in Settings, on the TV-couch model: it opens there and never
 * climbs above it. Rows open on touch, the D-pad (or volume keys) moves the highlight
 * and the list scrolls to follow it, OK plays.
 */
@Composable
fun MoviesScreen(remote: RemoteState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val root = remote.store.moviesPath.trim()
    var path by remember { mutableStateOf(root) }
    var items by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    fun atRoot(p: String) = root.isNotEmpty() &&
        p.trimEnd('\\', '/').equals(root.trimEnd('\\', '/'), ignoreCase = true)

    fun load(dir: String) {
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    Vlc.browse(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, dir)
                }
                // Locked to the assigned folder: no "up" once we are standing on it.
                val up = if (atRoot(dir)) emptyList() else raw.filter { it.name == ".." }
                val dirs = raw.filter { it.isDir && it.name != ".." }
                val files = raw.filter { !it.isDir && isVideoFile(it.name) }
                items = up + dirs + files
                selected = if (up.isNotEmpty()) 1.coerceAtMost(items.size - 1) else 0
                path = dir
                loading = false
                listState.scrollToItem(0)
            } catch (e: Exception) {
                loading = false
                error = if (root.isEmpty())
                    "Couldn't open that folder. Pick a Movies folder in Settings, and check the PC is on."
                else
                    "Couldn't open that folder. Check the Movies folder in Settings, and that the PC is on."
            }
        }
    }

    fun upOne() {
        val parent = path.trimEnd('\\', '/').substringBeforeLast('\\', "")
        if (parent.isNotBlank()) load(parent)
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

    LaunchedEffect(Unit) { load(if (root.isNotEmpty()) root else path) }

    // Inside a folder, Back goes up one level first (never above the assigned folder).
    BackHandler(enabled = root.isNotEmpty() && !atRoot(path)) { upOne() }

    // Volume keys steer the highlight too (wired from MainActivity).
    MoviesKeyHandler.register { key ->
        when (key) {
            MoviesKeyHandler.Key.UP -> if (selected > 0) selected--
            MoviesKeyHandler.Key.DOWN -> if (selected < items.size - 1) selected++
        }
    }
    DisposableEffect(Unit) { onDispose { MoviesKeyHandler.register(null) } }

    // The list scrolls to follow the highlight, so D-pad down never walks off-screen.
    LaunchedEffect(selected, items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(selected)
    }

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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            when {
                loading -> item { Text("Loading...", color = Muted, modifier = Modifier.padding(8.dp)) }
                error != null -> item { Text(error!!, color = ErrorRed, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
                items.isEmpty() -> item { Text("No videos in this folder.", color = Muted, modifier = Modifier.padding(8.dp)) }
                else -> items(items.size, key = { it }) { i ->
                    val item = items[i]
                    val isSel = i == selected
                    PressTile(
                        onTap = { selected = i; open(item) },
                        background = if (isSel) OrangeGradient else CardGradient,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        corner = 16.dp,
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        ) {
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
            "Tap a row, or use the arrows and OK. Volume keys work as arrows too.",
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
