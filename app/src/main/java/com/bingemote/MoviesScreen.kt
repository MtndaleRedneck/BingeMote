package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.bingemote.stream.PhoneMovies
import com.bingemote.stream.StreamCoordinator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File origin for the Movies screen: PC = the PC folder (VLC or helper); Phone = browse the
 * assigned Android folder and stream the file to VLC on the PC. File-level (not local) because
 * the K2 compiler crashes on enum-entry access from nested Compose lambdas over a local enum. */
enum class Origin(val label: String) { PC("PC"), PHONE("Phone") }

/**
 * The movies folder picked in Settings, on the TV-couch model: it opens there and never
 * climbs above it. Touch only: tap a folder to open it, tap a movie to play it,
 * "Up one folder" walks back out but never above the assigned folder.
 */

/** Root argument for load() in the current listing mode. Helper mode lists
 *  folder-relative paths ("" is the folder root) and its jail rejects rooted
 *  paths; VLC mode takes absolute paths. Passing the absolute root while the
 *  helper is active breaks the listing - the changed-root reconnect bug. */
internal fun rootArgForMode(helperMode: Boolean, absRoot: String): String =
    if (helperMode) "" else absRoot

@Composable
fun MoviesScreen(remote: RemoteState, listState: LazyListState, onBack: () -> Unit, demoResumeAsk: Boolean = false, demoPhone: Boolean = false) {
    val scope = rememberCoroutineScope()
    val root = remote.store.moviesPath.trim()
    var path by remember { mutableStateOf(root) }
    var items by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Orders loader results: a superseded load must not write items/path/loading/error (see LoadGate).
    val gate = remember { LoadGate() }
    // Where each folder was scrolled to before we walked into a child, so ".." lands
    // you back on the folder you came from instead of the top of the list.
    val scrollStack = remember { mutableStateListOf<Triple<String, Int, Int>>() }
    var origin by remember { mutableStateOf(if (demoPhone && BuildConfig.DEBUG) Origin.PHONE else Origin.PC) }
    val context = LocalContext.current
    // Asked ONCE at the first phone stream on Android 13+ so the stream service's
    // Stop action shows (teardown C1); the flag lives in the store, never nags again.
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askStreamNotifPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (remote.store.notifPermissionAsked) return
        remote.store.notifPermissionAsked = true
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Stack of (SAF folder document URI, display name) below the assigned tree root
    // (SAF has no parent lookup, so the stack is how "up" and the breadcrumb work).
    val phoneStack = remember { mutableStateListOf<Pair<Uri, String>>() }
    // Movie doc-uri string -> its sidecar subtitle URIs (same folder, matching base name).
    var phoneSubs by remember { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }

    fun phoneTree(): Uri? = remote.store.moviesPhoneTreeUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    // VLC closed? The helper (always in the tray) lists and plays instead. In helper mode
    // paths are relative to the movies folder.
    var viaHelper by remember { mutableStateOf(false) }
    // In-flight guard: a double-tap must not start two movies (two VLCs, two soundtracks).
    var starting by remember { mutableStateOf(false) }
    // PC unreachable: the same state the home hero reads. The approved Movies design shows
    // the orange reconnect banner under the header and reloads by itself when the PC is back.
    val lost = !remote.pcReachable && !remote.expectingVlc
    // The PC found a saved place in this movie: offer Resume / Start over (Preview 1).
    var resumeAsk by remember {
        mutableStateOf(
            // CI screenshot state (debug builds only): the dialog as he'd see it.
            if (demoResumeAsk && BuildConfig.DEBUG) ResumeAsk("demo.mkv", 34 * 60 + 12, 110 * 60 + 24) else null,
        )
    }

    /** Ask the PC to play a movies-folder file. resume: null = ask if a saved place
     * exists; true = resume; false = start over. */
    fun startPlay(relPath: String, resume: Boolean?) {
        if (starting) return
        starting = true
        scope.launch(Dispatchers.IO) {
            try {
                val resp = HelperClient.playMovie(
                    remote.store.host, remote.store.helperPort, remote.store.helperToken, relPath,
                    remote.store.vlcPort, remote.store.vlcPassword, fullscreen = true, resume = resume,
                )
                // Screen was dark: the tap only woke the PC. Nothing else happens - the
                // press sink was the feedback, and the next tap plays normally.
                if (resp.optBoolean("woke", false)) return@launch
                val ask = resp.optJSONObject("ask")
                if (ask != null) {
                    withContext(Dispatchers.Main) {
                        resumeAsk = ResumeAsk(relPath, ask.optLong("position", 0), ask.optLong("length", 0))
                    }
                } else {
                    remote.expectVlcStarting()
                    remote.retry()
                    withContext(Dispatchers.Main) { onBack() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "The PC could not start the movie." }
            } finally { starting = false }
        }
    }

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

    /** Absolute VLC path -> helper path relative to the movies folder. */
    fun toRel(dir: String): String {
        val root = remote.store.moviesPath.trim().trimEnd('\\', '/')
        if (root.isEmpty()) return ""
        val d = dir.trimEnd('\\', '/')
        return when {
            d.equals(root, ignoreCase = true) -> ""
            d.startsWith(root, ignoreCase = true) -> d.substring(root.length).trimStart('\\', '/')
            else -> ""
        }
    }

    /** Helper-relative path -> absolute VLC path. */
    fun toAbs(rel: String): String {
        val root = remote.store.moviesPath.trim().trimEnd('\\', '/')
        return if (rel.isEmpty()) root else root + "\\" + rel
    }

    fun loadPhone(restore: Pair<Int, Int>? = null) {
        val g = gate.bump()
        if (demoPhone && BuildConfig.DEBUG) {
            // CI screenshot state only: fake phone listing, no SAF access.
            items = listOf(
                BrowseItem(name = "Documentaries", isDir = true, path = "demo://d1", uri = "demo://d1", sizeBytes = 0L),
                BrowseItem(name = "The Big Example Movie (2024).mkv", isDir = false, path = "demo://m1", uri = "demo://m1", sizeBytes = 4_280_000_000L),
                BrowseItem(name = "Another Sample Film.mp4", isDir = false, path = "demo://m2", uri = "demo://m2", sizeBytes = 1_640_000_000L),
            )
            loading = false
            error = null
            return
        }

        val tree = phoneTree()
        if (tree == null) {
            loading = false
            items = emptyList()
            error = "Pick the Android movies folder first."
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val folder = phoneStack.lastOrNull()?.first
                    ?: PhoneMovies.childUri(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                val listing = withContext(Dispatchers.IO) { PhoneMovies.list(context, tree, folder) }
                if (!gate.isCurrent(g)) return@launch
                val rows = mutableListOf<BrowseItem>()
                if (!listing.atRoot) rows.add(BrowseItem(name = "..", isDir = true, path = "", uri = "", sizeBytes = 0L))
                listing.folders.forEach { f -> rows.add(BrowseItem(name = f.name, isDir = true, path = f.uri.toString(), uri = f.uri.toString(), sizeBytes = 0L)) }
                listing.movies.forEach { m -> rows.add(BrowseItem(name = m.name, isDir = false, path = m.uri.toString(), uri = m.uri.toString(), sizeBytes = m.sizeBytes)) }
                phoneSubs = listing.movies.associate { it.uri.toString() to it.subtitles }
                items = rows
                loading = false
                scope.launch { if (restore != null) listState.scrollToItem(restore.first, restore.second) else listState.scrollToItem(0) }
            } catch (e: Exception) {
                if (!gate.isCurrent(g)) return@launch
                loading = false
                error = plainPlayerError(e, "Couldn't open that folder on the phone.")
            }
        }
    }

    /** Phone-origin tap on a movie: serve it from the phone and hand the URL to VLC on the PC
     *  (in_play). Sidecar subtitles ride as :input-slave options. */
    fun streamPlay(item: BrowseItem) {
        if (starting) return
        askStreamNotifPermissionOnce()
        val receiver = try { StreamTarget.capture(remote.store) } catch (e: Exception) { error = plainPlayerError(e, "Couldn't start the stream."); return }
        starting = true
        scope.launch(Dispatchers.IO) {
            try {
                val bound = StreamCoordinator.bind(context)
                try {
                    val movieUri = Uri.parse(item.uri)
                    val (url, subs) = StreamCoordinator.handoff(bound, movieUri, item.name, phoneSubs[item.uri] ?: emptyList())
                    StreamTarget.play(receiver, url, subs)
                } finally {
                    StreamCoordinator.release(context, bound)
                }
                withContext(Dispatchers.Main) { onBack() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = plainPlayerError(e, "Couldn't start the stream.") }
            } finally { starting = false }
        }
    }

    fun loadVlc(dir: String, restore: Pair<Int, Int>?) {
        val g = gate.bump()
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    Vlc.browse(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, dir)
                }
                if (!gate.isCurrent(g)) return@launch
                show(raw, dir, false, restore)
            } catch (e: Exception) {
                if (!gate.isCurrent(g)) return@launch
                loading = false
                // Only a dead VLC/PC shows the connection message; a VLC error (bad folder,
                // VLC itself upset) is shown where he is - no mode flip, no jump to the top.
                error = if (e is Vlc.Unreachable)
                    "Couldn't open that folder. Check the Movies folder in Settings, and that the PC is on."
                else e.message ?: "Couldn't open that folder."
            }
        }
    }

    fun loadHelper(rel: String, restore: Pair<Int, Int>? = null, healed: Boolean = false) {
        val g = gate.bump()
        loading = true
        error = null
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    HelperClient.listMovies(remote.store.host, remote.store.helperPort, remote.store.helperToken, rel)
                }
                if (!gate.isCurrent(g)) return@launch
                val rows = raw.map { (name, dir) ->
                    BrowseItem(name = name, isDir = dir, path = if (rel.isEmpty()) name else "$rel\\$name", uri = "", sizeBytes = 0L)
                }
                show(rows, rel, rel.isEmpty(), restore)
            } catch (e: Exception) {
                if (!gate.isCurrent(g)) return@launch
                // Self-heal once: the PC may have lost or never received the folder. If the
                // PC's copy is empty or different, re-save the phone's copy and retry.
                if (!healed && remote.store.helperToken.isNotBlank() && remote.store.moviesPath.isNotBlank()) {
                    // heal: null = none, "push" = PC folder was blank and we pushed
                    // the phone's copy, "pull" = PC had a different folder and won.
                    val heal = withContext(Dispatchers.IO) {
                        try {
                            // Strict read: a failed get throws and lands in the catch
                            // below (no heal) - an unknown PC state must never look
                            // like "PC has none" and trigger a push over its folder.
                            val onPc = HelperClient.getMoviesFolderStrict(remote.store.host, remote.store.helperPort, remote.store.helperToken)
                            if (onPc.isBlank()) {
                                HelperClient.setMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken, remote.store.moviesPath.trim())
                                "push"
                            } else if (!onPc.trim().equals(remote.store.moviesPath.trim(), ignoreCase = true)) {
                                // The PC's setting wins: pull its folder instead of
                                // pushing the phone's stale copy over it.
                                remote.store.moviesPath = onPc
                                "pull"
                            } else null
                        } catch (e2: Exception) { null }
                    }
                    if (!gate.isCurrent(g)) return@launch
                    if (heal == "pull") {
                        // The folder changed under us: the relative path we were
                        // loading belongs to the old folder and may not exist under
                        // the new root, and saved navigation state is stale. Start
                        // at the new root, same as a folder change while connected.
                        scrollStack.clear()
                        loadHelper("", null, healed = true)
                        return@launch
                    }
                    if (heal == "push") { loadHelper(rel, restore, healed = true); return@launch }
                }
                if (e is HelperClient.ConnectTimeout) {
                    // The PC app can't answer at all - fall back to VLC for this listing.
                    viaHelper = false
                    loadVlc(toAbs(rel), restore)
                    return@launch
                }
                loading = false
                error = e.message?.takeIf { e is HelperClient.Unreachable } ?: "Couldn't open that folder on the PC."
            }
        }
    }

    fun load(dir: String, restore: Pair<Int, Int>? = null) {
        if (!remote.pcReachable) { gate.bump(); loading = false; return }
        if (viaHelper) { loadHelper(dir, restore); return }
        // The PC app first (it answers instantly whether VLC is open or closed - the same
        // order as the Settings folder picker); VLC is only the fallback. This skips the
        // 4-second wait on a closed VLC that made the tab feel slow.
        if (remote.store.helperToken.isNotBlank()) {
            viaHelper = true
            loadHelper(toRel(dir), restore)
            return
        }
        loadVlc(dir, restore)
    }

    fun switchOrigin(o: Origin) {
        if (origin == o) return
        origin = o
        error = null
        if (o == Origin.PHONE) loadPhone() else {
            // The other origin's rows must not stay on screen while the PC list loads or when the PC can't answer (disconnected).
            items = emptyList()
            loading = true
            load(path)
        }
    }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            remote.store.moviesPhoneTreeUri = uri.toString()
            phoneStack.clear()
            origin = Origin.PHONE
            items = emptyList()
            error = null
            loadPhone()
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
        if (origin == Origin.PHONE) {
            when {
                item.name == ".." -> {
                    if (phoneStack.isNotEmpty()) phoneStack.removeAt(phoneStack.size - 1)
                    loadPhone()
                }
                item.isDir -> {
                    phoneStack.add(Uri.parse(item.uri) to item.name)
                    loadPhone()
                }
                else -> streamPlay(item)
            }
            return
        }
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
            viaHelper -> startPlay(item.path, null)
            else -> {
                if (starting) return
                starting = true
                scope.launch(Dispatchers.IO) {
                    try {
                        Vlc.playUri(remote.store.host, remote.store.vlcPort, remote.store.vlcPassword, item.uri)
                        withContext(Dispatchers.Main) { onBack() }
                    } catch (e: Exception) {
                        // Used to fail silently and just close - stay put and say it didn't play.
                        withContext(Dispatchers.Main) { error = "Couldn't play that one." }
                    } finally { starting = false }
                }
            }
        }
    }

    // Auto-reload on reconnect: the 1s poll has already proven the PC answers before this
    // fires, so there is no Try again button - the screen just fills itself again.
    var wasLost by remember { mutableStateOf(false) }
    LaunchedEffect(lost) {
        if (lost) wasLost = true
        else if (wasLost) {
            wasLost = false
            // Same rule as screen entry: the PC's folder wins when the helper answers.
            // Without this, a folder changed on the PC during the outage kept the
            // phone's stale copy (and could push it back over the PC's choice).
            val g0 = gate.current()
            val fromPc = withContext(Dispatchers.IO) {
                if (remote.store.helperToken.isBlank()) null
                else HelperClient.getMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken)
            }
            // Superseded by a newer load, or the user is on the phone list now: do not write the PC folder or reload over it.
            if (!gate.isCurrent(g0) || origin == Origin.PHONE) return@LaunchedEffect
            if (!fromPc.isNullOrBlank() && !fromPc.trim().equals(remote.store.moviesPath.trim(), ignoreCase = true)) {
                // New root: the old relative path and saved scroll spots no longer
                // exist, so viewport preservation only applies within the same folder.
                remote.store.moviesPath = fromPc
                scrollStack.clear()
                load(rootArgForMode(viaHelper, remote.store.moviesPath.trim()))
            } else {
                load(path, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
            }
        }
    }

    // The PC's setting wins when the helper has one; the phone's copy is the fallback.
    LaunchedEffect(Unit) {
        if (!remote.pcReachable) { gate.bump(); loading = false; return@LaunchedEffect }
        val g0 = gate.current()
        val fromPc = withContext(Dispatchers.IO) {
            if (remote.store.helperToken.isBlank()) null
            else HelperClient.getMoviesFolder(remote.store.host, remote.store.helperPort, remote.store.helperToken)
        }
        if (!gate.isCurrent(g0)) return@LaunchedEffect // a load started while the PC answered: it owns the screen now
        if (!fromPc.isNullOrBlank()) remote.store.moviesPath = fromPc
        if (demoPhone && BuildConfig.DEBUG) loadPhone() else load(remote.store.moviesPath.trim().ifBlank { path })
    }

    // Inside a folder, Back goes up one level first (never above the assigned folder).
    val insidePhoneFolder = origin == Origin.PHONE && phoneStack.isNotEmpty()
    BackHandler(enabled = insidePhoneFolder) {
        phoneStack.removeAt(phoneStack.size - 1)
        loadPhone()
    }
    val insideFolder = origin == Origin.PC && root.isNotEmpty() && !atRoot(path)
    BackHandler(enabled = insideFolder) { upOne() }
    BackHandler(enabled = !insideFolder && !insidePhoneFolder) { onBack() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Movies", hapticsOn = remote.store.haptics, onBack = onBack)
        // The reconnect banner, verbatim from the home hero: same gradient, radius, ink,
        // sizes and strings. The Movies title and back arrow stay put above it.
        if (lost) {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(20.dp), ambientColor = Orange, spotColor = Orange)
                    .clip(RoundedCornerShape(20.dp))
                    .background(OrangeGradient)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column {
                    Text("Trying to reconnect", color = Color(0xFF1B1B1E), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("Both devices need to be on the same network", color = Color(0xFF3D2A12), fontSize = 14.sp, letterSpacing = 0.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        // Root breadcrumb: only rendered when there is a path to show, so the root
        // view has no blank gap under the header.
        val crumbs = prettyPath(path)
        if (origin == Origin.PC && crumbs.isNotBlank()) {
            Text(
                crumbs,
                color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        // The PC origin's breadcrumb is the single one above (as in BingeMote); the Phone origin shows its own folder.
        if (origin == Origin.PHONE) {
            Text(
                phoneFolderDisplay(remote.store.moviesPhoneTreeUri) + phoneStack.joinToString("") { " › " + it.second },
                color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        // Origin selector: orange = active origin (dark text), muted = inactive.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Card),
        ) {
            listOf(Origin.PC, Origin.PHONE).forEach { o ->
                val sel = origin == o
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) OrangeGradient else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Card, Card)))
                        .clickable { switchOrigin(o) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o.label, color = if (sel) Bg else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            when {
                // While the banner says the PC is gone, the list area stays quiet: no spinner,
                // no error card, no wrong "No videos" - just the last list if there is one.
                origin == Origin.PC && lost && items.isEmpty() && !loading -> item { Spacer(Modifier.height(1.dp)) }
                loading && (!lost || origin == Origin.PHONE) -> item { ListMessage("Loading\u2026", loading = true) }
                error != null && (!lost || origin == Origin.PHONE) -> item {
                    if (origin == Origin.PHONE && phoneTree() == null)
                        ListMessage(error!!, isError = true, actionLabel = "Pick folder", onAction = { pickTree.launch(DebugRig.pickerInitialUri) })
                    else
                        ListMessage(error!!, isError = true, actionLabel = "Try again", onAction = { if (origin == Origin.PHONE) loadPhone() else load(path) })
                }
                items.isEmpty() -> item { ListMessage("No videos in this folder.") }
                else -> items(items.size, key = { items[it].path }, contentType = { items[it].isDir }) { i ->
                    val item = items[i]
                    PressTile(
                        onTap = { if (remote.pcReachable || origin == Origin.PHONE) open(item) },
                        // Reuse the existing Store rather than constructing encrypted
                        // preferences for each row during composition and scrolling.
                        hapticsOn = remote.store.haptics,
                        background = CardGradient,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        corner = 16.dp,
                    ) {
                        BrowseRowContent(
                            name = if (item.name == "..") "Up one folder" else if (item.isDir) item.name else displayName(item.name),
                            kind = when {
                                item.name == ".." -> BrowseKind.Up
                                item.isDir -> BrowseKind.Folder
                                else -> BrowseKind.Movie
                            },
                            detail = if (!item.isDir && item.sizeBytes > 0) formatSize(item.sizeBytes) else null,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
    resumeAsk?.let { ask ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { resumeAsk = null },
            title = { Text("Keep watching?", color = TextC, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You're ${fmtClock(ask.positionSec)} in (of ${fmtClock(ask.lengthSec)}).",
                    color = TextC,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    resumeAsk = null
                    startPlay(ask.path, true)
                }) { Text("Resume", color = Orange, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    resumeAsk = null
                    startPlay(ask.path, false)
                }) { Text("Start over", color = Muted) }
            },
            containerColor = Card,
        )
    }
}



/** The saved place the PC reported for a movie. */
private data class ResumeAsk(val path: String, val positionSec: Long, val lengthSec: Long)

/** 34:12 or 1:50:24. */
private fun fmtClock(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/** "C:\Users\you\Desktop\Movies" -> "Desktop > Movies" for the path line. */
fun prettyPath(path: String): String {
    val parts = path.replace('/', '\\').split('\\').filter { it.isNotBlank() && !it.endsWith(":") }
    return parts.takeLast(2).joinToString(" \u203A ").ifBlank { path }
}


/** Raw plumbing never reaches the screen: socket text, timeouts and the stream server's
 *  "server not ready" each get one plain sentence; anything else gets the caller's fallback. */
internal fun plainPlayerError(e: Exception, fallback: String): String {
    val m = e.message ?: return fallback
    if (e is HelperClient.BadPairCode || e is HelperClient.BadToken || e is HelperClient.Unreachable) return m
    if (m.contains("server not ready", true) || m.contains("no wi-fi", true) || m.contains("network is unreachable", true))
        return "Your phone needs to be on Wi-Fi to play from it."
    if (m.contains("failed to connect", true) || m.contains("connection refused", true)
        || m.contains("connect timed out", true) || m.contains("timeout", true)
        || m.contains("unable to resolve host", true) || m.contains("connection reset", true)
        || m.contains("no route to host", true) || m.contains("end of stream", true)
        || m.contains("software caused connection abort", true))
        return "Can't reach the PC. Check it's on and on the same Wi-Fi, then try again."
    return fallback
}
