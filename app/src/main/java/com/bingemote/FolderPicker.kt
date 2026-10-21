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
 */
@Composable
fun FolderPickerScreen(remote: RemoteState, listState: LazyListState, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(remote.store.moviesPath.trim()) }
    var dirs by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Where each folder was scrolled to before we walked into a child, so going back
    // up lands you where you were instead of back at the top.
    val scrollStack = remember { mutableStateListOf<Triple<String, Int, Int>>() }

    fun load(dir: String, restore: Pair<Int, Int>? = null) {
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    Vlc.browse(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, dir)
                }
                dirs = raw.filter { it.isDir }
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
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(onCancel) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Pick your movies folder", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                dirs.isEmpty() -> item { Text("No folders here.", color = Muted, modifier = Modifier.padding(8.dp)) }
                else -> items(dirs.size, key = { it }) { i ->
                    val item = dirs[i]
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
                        Text(
                            if (item.name == "..") "Up one folder" else item.name,
                            color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        )
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
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (remote.store.helperToken.isNotBlank())
                                HelperClient.setMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken, path)
                        } catch (e: Exception) { /* the PC keeps its copy; the phone's still works */ }
                    }
                    onPick(path)
                },
                background = OrangeGradient,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                corner = 16.dp,
            ) {
                Text(
                    "Select this folder",
                    color = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel", color = Muted) }
        }
    }
}
