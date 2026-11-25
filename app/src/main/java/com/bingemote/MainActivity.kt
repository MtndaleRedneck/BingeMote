package com.bingemote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        /** Screenshot-rig only: arms the subtitle sheet's lose-connection-mid-view
         *  transition (see the subtitlepreviewlost intent extra). Debug builds only. */
        var previewLostRigArmed = false
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val a = ev.actionMasked
        // The one place every raw touch enters the app (the Compose root view
        // overrides dispatchTouchEvent, so a view-level OnTouchListener never
        // fires). A system cancel marks the gesture: it must not seek.
        if (a == android.view.MotionEvent.ACTION_CANCEL) RawTouch.cancelAt = ev.eventTime
        if (BuildConfig.DEBUG && (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)) {
            android.util.Log.i("BMSeek", "raw action=$a")
        }
        return super.dispatchTouchEvent(ev)
    }

    private lateinit var store: Store
    private lateinit var remote: RemoteState
    private var screen: MutableState<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test isolation: never inherit the transition rig from an earlier preview
        // Activity in the same process (interrupted test, config change).
        if (BuildConfig.DEBUG) previewLostRigArmed = false
        store = Store(this)
        // Screenshot rig: a stand-in PC name so the Your PC card renders paired.
        if (BuildConfig.DEBUG && intent.hasExtra("pcdemo")) store.pcName = "MY-PC"
        remote = RemoteState(this, store, lifecycleScope)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demostatus", false)) remote.debugSeedStatus()
        // Screenshot-rig only: render one status-notice review scenario per capture.
        // Polling is frozen (setupDone=false -> pollOnce no-ops) or leftover rig prefs
        // would re-trip unreachable/helperDown over the forced states mid-capture.
        if (BuildConfig.DEBUG && intent.hasExtra("statuscase")) store.setupDone = false
        if (BuildConfig.DEBUG) when (intent.getStringExtra("statuscase")) {
            "cur-g" -> remote.debugForceStatus(unreachable = true, helperDown = true)
            "prop-g" -> remote.debugForceStatus(unreachable = true, helperDown = false)
            "cur-e" -> { remote.debugSeedStatus(); remote.debugForceStatus(unreachable = false, helperDown = true) }
            // Genuinely connected: VLC answering AND helper answering (the sheet's
            // below-Off content only renders in this state since 1.12.16).
            "cur-ok" -> { remote.debugSeedStatus(); remote.debugForceStatus(unreachable = false, helperDown = false) }
            "prop-e" -> { remote.debugSeedStatus(); remote.mockHeroNote = "BingeMote on the PC isn't answering - open it to use mouse, keyboard and PC volume." }
            "prop-unpaired" -> { remote.debugSeedStatus(); remote.mockHeroNote = "This phone isn't paired with the PC anymore - pair again." }
            // Post-1.11.0 production states: the REAL shipped notice branches
            // (post-debounce helper notice / 401 pair-again), no mock slot.
            "prod-e" -> { remote.debugSeedStatus(); remote.debugForceStatus(unreachable = false, helperDown = true, helperNotice = true) }
            "prod-unpaired" -> { remote.debugSeedStatus(); remote.debugForceStatus(unreachable = false, helperDown = true, unpaired = true) }
        }
        // Subtitle previews seed AFTER any statuscase so a seeding case (cur-e etc.)
        // can't wipe the preview's tracks, and the case's connected/disconnected
        // flags still govern what the sheet shows.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("subtitlepreview", false)) remote.debugSubtitlePreview()
        // Screenshot-rig only: the sheet opens with connected content, then the
        // connection drops mid-view so UI tests see the live transition to Off-only.
        // The drop is armed here and fired by the sheet once the connected rows have
        // actually composed (never on a timer racing cold startup). Polling stays
        // frozen (statuscase sets setupDone=false), so nothing flips it back.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("subtitlepreviewlost", false)) {
            remote.debugSubtitlePreview()
            previewLostRigArmed = true
        }
        // UI-test rig only: seeds the stale folder-message state AFTER the sheet's
        // opening check has run, so the next failing quiet check is what clears it.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("subtitleseedstaleerror", false)) {
            lifecycleScope.launch {
                kotlinx.coroutines.delay(3000)
                remote.debugSeedSubtitleListError()
            }
        }
        if (BuildConfig.DEBUG && intent.hasExtra("lockcontrols")) {
            store.lockScreenControls = true
            store.demoControls = true
            MediaControls.sync(this, store)
        }
        // CI screenshot state: force the update offer dialog with mock release notes.
        if (BuildConfig.DEBUG && intent.getStringExtra("screen") == "update") {
            ManualUpdate.offer = UpdateInfo(
                versionName = "9.9.9",
                notes = listOf("## v9.9.9", "Example improvement one", "Example improvement two"),
                apkUrl = "",
                size = 0,
                sha256 = null,
            )
        }
        setContent {
            val current = remember { mutableStateOf(
                if (BuildConfig.DEBUG && intent.hasExtra("screen")) intent.getStringExtra("screen") ?: "setup"
                else if (store.setupDone) "main" else "setup") }
            screen = current
            // Scroll positions live above the screen switch so leaving a screen and
            // coming back lands you where you were. the Settings rule (2026-11-24):
            // back from a subpage (Feedback, Your PC, pair re-entry) returns to the
            // same spot; only opening Settings fresh from Home starts at the top.
            val settingsScroll = rememberScrollState(initial = if (BuildConfig.DEBUG && intent.hasExtra("updateResult")) Int.MAX_VALUE else 0)
            val navigationScope = rememberCoroutineScope()
            fun openSettings(resetScroll: Boolean = false) {
                navigationScope.launch {
                    if (resetScroll) settingsScroll.scrollTo(0)
                    current.value = "settings"
                }
            }
            if (BuildConfig.DEBUG && intent.hasExtra("updateResult")) {
                androidx.compose.runtime.LaunchedEffect(settingsScroll.maxValue) {
                    if (settingsScroll.maxValue > 0 && settingsScroll.maxValue < Int.MAX_VALUE)
                        settingsScroll.scrollTo(settingsScroll.maxValue)
                }
            }
            val setupScroll = rememberScrollState()
            val yourpcScroll = rememberScrollState()
            val feedbackScroll = rememberScrollState()
            val moviesListState = rememberLazyListState()
            val pickerListState = rememberLazyListState()
            // Fresh entry to a page starts at the top; Back to a parent keeps its place.
            fun enter(screen: String, scroll: ScrollState) {
                navigationScope.launch { scroll.scrollTo(0); current.value = screen }
            }
            BingeMoteTheme {
                when (current.value) {
                    "setup" -> SetupScreen(
                        remote,
                        setupScroll,
                        onDone = { remote.start(); current.value = "main" },
                        onCancel = if (store.setupDone) ({ openSettings() }) else null,
                        demoVlcPending = BuildConfig.DEBUG && intent.hasExtra("vlcpending"),
                        demoPair = BuildConfig.DEBUG && intent.hasExtra("pairdemo"),
                    )
                    "mouse" -> MouseScreen(remote) { current.value = "main" }
                    // CI screenshot states (debug builds only): open with the sheet forced open.
                    "keyboard" -> MouseScreen(remote, startKeyboardOpen = true) { current.value = "main" }
                    "subtitles" -> MainScreen(
                        remote,
                        onMouse = { current.value = "mouse" },
                        onMovies = { current.value = "movies" },
                        onSettings = { openSettings(resetScroll = true) },
                        startSubtitlesOpen = true,
                    )
                    "movies" -> MoviesScreen(remote, moviesListState, onBack = { current.value = "main" }, demoResumeAsk = BuildConfig.DEBUG && intent.hasExtra("resumeask"))
                    // CI screenshot state (debug builds only): the resume dialog over the list.
                    "movies-resume" -> MoviesScreen(remote, moviesListState, onBack = { current.value = "main" }, demoResumeAsk = BuildConfig.DEBUG)
                    "settings" -> SettingsScreen(
                        remote,
                        settingsScroll,
                        pickerListState,
                        onBack = { current.value = "main" },
                        onRepair = { enter("yourpc", yourpcScroll) },
                        onFeedback = { enter("feedback", feedbackScroll) },
                    )
                    "yourpc" -> YourPCScreen(
                        remote,
                        yourpcScroll,
                        onBack = { openSettings() },
                        onPairAgain = { enter("setup", setupScroll) },
                    )
                    "feedback" -> FeedbackScreen(feedbackScroll) { openSettings() }
                    else -> MainScreen(
                        remote,
                        onMouse = { current.value = "mouse" },
                        onMovies = { current.value = "movies" },
                        onSettings = { openSettings(resetScroll = true) },
                    )
                }
                UpdateHost(store)
            }
        }
    }

    private val notifPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { MediaControls.sync(this, store) }

    override fun onStart() {
        MediaControls.permissionRequest = { notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        super.onStart()
        if (::remote.isInitialized && store.setupDone) remote.start()
        MediaControls.sync(this, store)
    }

    override fun onStop() {
        if (::remote.isInitialized) remote.stop()
        // A drag can be latched when the app backgrounds (call, screen timeout): release the
        // PC's left button so it doesn't stay held down while he's away.
        if (::remote.isInitialized && store.helperToken.isNotBlank()) {
            val s = store
            HelperClient.io.execute { try { HelperClient.button(s.host, s.helperPort, s.helperToken, "left", false) } catch (_: Exception) {} }
        }
        super.onStop()
    }

    /** Volume buttons control VLC volume (the movie list is touch only). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::remote.isInitialized || !store.setupDone) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { remote.volumeStep(true); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { remote.volumeStep(false); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

/** One-slot hand-off from the Settings update button to the offer dialog. */
object ManualUpdate {
    var offer by mutableStateOf<UpdateInfo?>(null)
}

private sealed interface UpdateUi {
    data class Offer(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * The always-on update check: asks GitHub once per open, offers the update,
 * and stays quiet when the network or GitHub is unavailable. Adapted from Listen Later.
 */
@Composable
fun UpdateHost(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }

    fun launchInstaller(f: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: Exception) {
            updateUi = UpdateUi.Failed("Could not open the installer.")
        }
    }

    fun startUpdate(info: UpdateInfo) {
        updateUi = UpdateUi.Downloading(info, 0f)
        updateJob = scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val existing = File(Updater.updateDir(context), "BingeMote-update.apk")
                    if (existing.exists()) {
                        try { Updater.verify(context, existing); return@withContext existing }
                        catch (e: Exception) { existing.delete() }
                    }
                    val file = Updater.download(
                        context, info,
                        { pr -> updateUi = UpdateUi.Downloading(info, pr.coerceIn(0f, 1f)) },
                        { !isActive },
                    )
                    try { Updater.verify(context, file) }
                    catch (e: Updater.Failure) { file.delete(); throw e }
                    file
                }
                updateUi = null
                if (context.packageManager.canRequestPackageInstalls()) {
                    launchInstaller(f)
                } else {
                    updateUi = UpdateUi.Failed("Allow BingeMote to install updates, then check for updates again in Settings.")
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    )
                }
            } catch (e: Updater.Failure) {
                updateUi = if (e.msg == "cancelled") null else UpdateUi.Failed(e.msg)
            } catch (e: CancellationException) {
                Updater.clean(context)
                updateUi = null
            } catch (e: Exception) {
                Updater.clean(context)
                updateUi = UpdateUi.Failed("The update could not be installed.")
            }
        }
    }

    LaunchedEffect(Unit) {
        val current = BuildConfig.VERSION_NAME
        val info = withContext(Dispatchers.IO) { Updater.check(current) } ?: return@LaunchedEffect
        if (info.versionName == store.skippedUpdate) return@LaunchedEffect
        updateUi = UpdateUi.Offer(info)
    }

    // The Settings "Check for updates" button drops its find here; a manual ask always
    // shows the offer, even for a version he skipped before.
    LaunchedEffect(ManualUpdate.offer) {
        ManualUpdate.offer?.let {
            updateUi = UpdateUi.Offer(it)
            ManualUpdate.offer = null
        }
    }

    updateUi?.let { u ->
        when (u) {
            is UpdateUi.Offer -> AlertDialog(
                onDismissRequest = { updateUi = null },
                containerColor = Card2,
                title = { Text("BingeMote ${u.info.versionName} is available", color = TextC) },
                text = {
                    Column {
                        Text("You have ${BuildConfig.VERSION_NAME}.", color = Muted)
                        if (u.info.notes.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            val maxNotes = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
                            Column(Modifier.heightIn(max = maxNotes).verticalScroll(rememberScrollState())) {
                                u.info.notes.forEach {
                                    if (it.startsWith("## ")) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it.drop(3), fontWeight = FontWeight.Bold, color = TextC)
                                    } else {
                                        Text("\u2022 $it", color = TextC)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton({ startUpdate(u.info) }) { Text("Update", color = Orange, fontWeight = FontWeight.Bold) } },
                // Skip sits with the other choices instead of floating, indented, inside the notes.
                dismissButton = {
                    Row {
                        TextButton({ store.skippedUpdate = u.info.versionName; updateUi = null }) { Text("Skip this version", color = Muted) }
                        TextButton({ updateUi = null }) { Text("Later", color = Muted) }
                    }
                },
            )
            is UpdateUi.Downloading -> AlertDialog(
                onDismissRequest = {},
                containerColor = Card2,
                title = { Text("Downloading ${u.info.versionName}", color = TextC) },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { u.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Orange,
                            trackColor = Card,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${(u.progress * 100).toInt()}%", color = Muted)
                    }
                },
                confirmButton = { TextButton({ updateJob?.cancel() }) { Text("Cancel", color = Muted) } },
            )
            is UpdateUi.Failed -> AlertDialog(
                onDismissRequest = { updateUi = null },
                containerColor = Card2,
                title = { Text("Update not installed", color = TextC) },
                text = { Text(u.message, color = Muted) },
                confirmButton = { TextButton({ updateUi = null }) { Text("OK", color = Orange) } },
            )
        }
    }
}
