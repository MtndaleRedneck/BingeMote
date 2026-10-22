package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick the PC's movies folder by browsing instead of typing: walk into folders with a
 * tap, back out with "Up one folder", and confirm with "Select this folder".
 * Browsing asks the BingeMote app on the PC first, so it works with VLC closed
 * (drives > Users > ... > the folder). An older PC app without folder browsing falls
 * back to browsing through VLC, which needs VLC open.
 */
@Composable
fun FolderPickerScreen(remote: RemoteState, listState: LazyListState, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(remote.store.moviesPath.trim()) }
    var dirs by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Helper mode lists folders from the PC app (no VLC needed); VLC mode keeps
    // VLC's own "Up one folder" rows and paths exactly as they come back.
    var viaHelper by remember { mutableStateOf(false) }
    // Where each folder was scrolled to before we walked into a child, so going back
    // up lands you where you were instead of back at the top.
    val scrollStack = remember { mutableStateListOf<Triple<String, Int, Int>>() }

    fun childPath(dir: String, name: String) =
        if (dir.isEmpty()) name else dir.trimEnd('\\', '/') + "\\" + name

    fun parentPath(dir: String): String {
        val d = dir.trimEnd('\\', '/')
        // A drive root ("C:") goes up to the drives list; "" is already there.
        if (d.length <= 3) return ""
        val i = d.lastIndexOfAny(charArrayOf('\\', '/'))
        if (i < 0) return ""
        val p = d.substring(0, i)
        // Keep the root slash: a bare "C:" is drive-relative on Windows, not the root.
        return if (p.length == 2 && p[1] == ':') p + d[i] else p
    }

    fun load(dir: String, restore: Pair<Int, Int>? = null) {
        loading = true
        error = null
        scope.launch {
            // The PC app first: it answers with VLC closed. Too old or unreachable: VLC.
            if (remote.store.helperToken.isNotBlank()) {
                try {
                    val names = withContext(Dispatchers.IO) {
                        HelperClient.browse(remote.store.host, remote.store.helperPort, remote.store.helperToken, dir)
                    }
                    dirs = names.map { BrowseItem(name = it, isDir = true, path = childPath(dir, it), uri = "", sizeBytes = 0L) }
                    viaHelper = true
                    path = dir
                    loading = false
                    if (restore != null) listState.scrollToItem(restore.first, restore.second)
                    else listState.scrollToItem(0)
                    return@launch
                } catch (e: Exception) { /* old PC app or unreachable - try VLC below */ }
            }
            try {
                val raw = withContext(Dispatchers.IO) {
                    Vlc.browse(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, dir)
                }
                dirs = raw.filter { it.isDir }
                viaHelper = false
                path = dir
                loading = false
                if (restore != null) listState.scrollToItem(restore.first, restore.second)
                else listState.scrollToItem(0)
            } catch (e: Exception) {
                loading = false
                error = "Couldn't open that folder. Check the PC is on and VLC is running."
            }
        }
    }

    LaunchedEffect(Unit) { load(path) }
    BackHandler { onCancel() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(onCancel, remote.store.haptics) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Pick your movies folder", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            if (path.isEmpty()) "All drives" else prettyPath(path),
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
                loading -> item { ListMessage("Loading\u2026", loading = true) }
                error != null -> item { ListMessage(error!!, isError = true, actionLabel = "Try again", onAction = { load(path) }) }
                dirs.isEmpty() && path.isEmpty() -> item { ListMessage("No folders here.") }
                else -> {
                    val rows = if (viaHelper && path.isNotEmpty())
                        listOf(BrowseItem(name = "..", isDir = true, path = parentPath(path), uri = "", sizeBytes = 0L)) + dirs
                        else dirs
                    items(rows.size, key = { it }) { i ->
                        val item = rows[i]
                        PressTile(
                            onTap = {
                                if (item.name == "..") {
                                    val top = scrollStack.lastOrNull()
                                    val restore = if (top != null && top.first == item.path) {
                                        scrollStack.removeAt(scrollStack.size - 1)
                                        top.second to top.third
                                    } else null
                                    load(item.path, restore)
                                } else {
                                    scrollStack.add(Triple(path, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
                                    load(item.path)
                                }
                            },
                            background = CardGradient,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            corner = 16.dp,
                        ) {
                            BrowseRowContent(
                                name = if (item.name == "..") "Up one folder" else item.name,
                                kind = if (item.name == "..") BrowseKind.Up else BrowseKind.Folder,
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PressTile(
                onTap = {
                    if (saving || path.isEmpty()) return@PressTile
                    saving = true
                    saveError = null
                    scope.launch {
                        // Save on the PC so Movies works with VLC closed; only then close.
                        val saved = withContext(Dispatchers.IO) {
                            if (remote.store.helperToken.isBlank()) false
                            else try {
                                HelperClient.setMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken, path)
                                true
                            } catch (e: Exception) { false }
                        }
                        saving = false
                        if (saved) onPick(path)
                        else saveError = "Couldn't save that on the PC. Check the connection and tap Select this folder again."
                    }
                },
                background = OrangeGradient,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                corner = 14.dp,
                // Dimmed at "All drives", where there is nothing to select yet.
                enabled = path.isNotEmpty() && !saving,
            ) {
                Text(
                    if (saving) "Saving\u2026" else "Select this folder",
                    color = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
            saveError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = ErrorRed, fontSize = 13.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            TextButton(onClick = onCancel) { Text("Cancel", color = Muted) }
        }
    }
}
