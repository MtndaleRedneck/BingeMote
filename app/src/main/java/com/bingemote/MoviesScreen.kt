package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
 * climbs above it. Touch only: tap a folder to open it, tap a movie to play it,
 * "Up one folder" walks back out but never above the assigned folder.
 */
@Composable
fun MoviesScreen(remote: RemoteState, listState: LazyListState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val root = remote.store.moviesPath.trim()
    var path by remember { mutableStateOf(root) }
    var items by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Where each folder was scrolled to before we walked into a child, so ".." lands
    // you back on the folder you came from instead of the top of the list.
    val scrollStack = remember { mutableStateListOf<Triple<String, Int, Int>>() }
    // VLC closed? The helper (always in the tray) lists and plays instead. In helper mode
    // paths are relative to the movies folder.
    var viaHelper by remember { mutableStateOf(false) }

    fun atRoot(p: String) =
        if (viaHelper) p.isEmpty()
        else root.isNotEmpty() && p.trimEnd('\\', '/').equals(root.trimEnd('\\', '/'), ignoreCase = true)

    fun show(raw: List<BrowseItem>, dir: String, helperAtRoot: Boolean, restore: Pair<Int, Int>? = null) {
        // Locked to the assigned folder: no "up" once we are standing on it. The helper's
        // listing has no ".." row of its own, so make one from the relative path.
        val up = when {
            atRoot(dir) || helperAtRoot -> emptyList()
            viaHelper -> listOf(BrowseItem(name = "..", isDir = true, path = dir.substringBeforeLast('\\', ""), uri = "", sizeBytes = 0L))
            else -> raw.filter { it.name == ".." }
        }
        val dirs = raw.filter { it.isDir && it.name != ".." }
        val files = raw.filter { !it.isDir && isVideoFile(it.name) }
        items = up + dirs + files
        path = dir
        loading = false
        scope.launch {
            if (restore != null) listState.scrollToItem(restore.first, restore.second)
            else listState.scrollToItem(0)
        }
    }

    fun loadHelper(rel: String, restore: Pair<Int, Int>? = null, healed: Boolean = false) {
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    HelperClient.listMovies(remote.store.host, remote.store.helperPort, remote.store.helperToken, rel)
                }
                val rows = raw.map { (name, dir) ->
                    BrowseItem(name = name, isDir = dir, path = if (rel.isEmpty()) name else "$rel\\$name", uri = "", sizeBytes = 0L)
                }
                show(rows, rel, rel.isEmpty(), restore)
            } catch (e: Exception) {
                // Self-heal once: the PC may have lost or never received the folder. If the
                // PC's copy is empty or different, re-save the phone's copy and retry.
                if (!healed && remote.store.helperToken.isNotBlank() && remote.store.moviesPath.isNotBlank()) {
                    val fixed = withContext(Dispatchers.IO) {
                        try {
                            val onPc = HelperClient.getMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken)
                            if (onPc.isNullOrBlank() || !onPc.trim().equals(remote.store.moviesPath.trim(), ignoreCase = true)) {
                                HelperClient.setMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken, remote.store.moviesPath.trim())
                                true
                            } else false
                        } catch (e2: Exception) { false }
                    }
                    if (fixed) { loadHelper(rel, restore, healed = true); return@launch }
                }
                loading = false
                error = e.message ?: "Couldn't open that folder on the PC."
            }
        }
    }

    fun load(dir: String, restore: Pair<Int, Int>? = null) {
        if (viaHelper) { loadHelper(dir); return }
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    Vlc.browse(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, dir)
                }
                show(raw, dir, false, restore)
            } catch (e: Exception) {
                if (remote.store.helperToken.isNotBlank()) {
                    // VLC is closed - the helper takes over, relative to the movies folder.
                    // Clear the stale absolute path first: helper paths are relative, and
                    // keeping the old one traps Back in a 404 loop (atRoot never true).
                    viaHelper = true
                    path = ""
                    loadHelper("")
                } else {
                    loading = false
                    error = "Couldn't open that folder. Check the Movies folder in Settings, and that the PC is on."
                }
            }
        }
    }

    fun upOne() {
        fun popPos(forPath: String): Pair<Int, Int>? {
            val top = scrollStack.lastOrNull() ?: return null
            if (top.first != forPath) return null
            scrollStack.removeAt(scrollStack.size - 1)
            return top.second to top.third
        }
        if (viaHelper) {
            val parent = path.substringBeforeLast('\\', "")
            loadHelper(parent, popPos(parent))
            return
        }
        val parent = path.trimEnd('\\', '/').substringBeforeLast('\\', "")
        if (parent.isNotBlank()) load(parent, popPos(parent))
    }

    fun open(item: BrowseItem) {
        when {
            item.isDir -> {
                if (item.name == "..") {
                    // Walking up: drop the spot we saved for this folder's parent on the
                    // way down and land back on it, instead of jumping to the top.
                    val top = scrollStack.lastOrNull()
                    val restore = if (top != null && top.first == item.path) {
                        scrollStack.removeAt(scrollStack.size - 1)
                        top.second to top.third
                    } else null
                    if (viaHelper) loadHelper(item.path, restore) else load(item.path, restore)
                } else {
                    scrollStack.add(Triple(path, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
                    if (viaHelper) loadHelper(item.path) else load(item.path)
                }
            }
            viaHelper -> {
                scope.launch(Dispatchers.IO) {
                    val failed = HelperClient.playMovie(
                        remote.store.host, remote.store.helperPort, remote.store.helperToken, item.path,
                        remote.store.vlcPort, remote.store.vlcPassword, fullscreen = true,
                    )
                    if (failed == null) {
                        remote.expectVlcStarting()
                        remote.retry()
                        withContext(Dispatchers.Main) { onBack() }
                    } else {
                        withContext(Dispatchers.Main) { error = failed }
                    }
                }
            }
            else -> {
                scope.launch(Dispatchers.IO) {
                    try { Vlc.playUri(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, item.uri) } catch (e: Exception) { }
                }
                onBack()
            }
        }
    }

    // The PC's setting wins when the helper has one; the phone's copy is the fallback.
    LaunchedEffect(Unit) {
        val fromPc = withContext(Dispatchers.IO) {
            if (remote.store.helperToken.isBlank()) null
            else HelperClient.getMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken)
        }
        if (!fromPc.isNullOrBlank()) remote.store.moviesPath = fromPc
        load(remote.store.moviesPath.trim().ifBlank { path })
    }

    // Inside a folder, Back goes up one level first (never above the assigned folder).
    val insideFolder = root.isNotEmpty() && !atRoot(path)
    BackHandler(enabled = insideFolder) { upOne() }
    BackHandler(enabled = !insideFolder) { onBack() }

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
                    PressTile(
                        onTap = { open(item) },
                        background = CardGradient,
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
                                color = TextC,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (!item.isDir && item.sizeBytes > 0) {
                                Text(
                                    formatSize(item.sizeBytes),
                                    color = Muted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/** "C:\Users\you\Desktop\Movies" -> "Desktop > Movies" for the path line. */
fun prettyPath(path: String): String {
    val parts = path.replace('/', '\\').split('\\').filter { it.isNotBlank() && !it.endsWith(":") }
    return parts.takeLast(2).joinToString(" > ").ifBlank { path }
}
