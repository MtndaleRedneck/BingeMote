package com.bingemote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
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

    private lateinit var store: Store
    private lateinit var remote: RemoteState
    private var screen: MutableState<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        remote = RemoteState(this, store, lifecycleScope)
        setContent {
            val current = remember { mutableStateOf(
                if (BuildConfig.DEBUG && intent.hasExtra("screen")) intent.getStringExtra("screen") ?: "setup"
                else if (store.setupDone) "main" else "setup") }
            screen = current
            // Scroll positions live above the screen switch so leaving a screen and
            // coming back lands you where you were instead of back at the top.
            val settingsScroll = rememberScrollState()
            val setupScroll = rememberScrollState()
            val feedbackScroll = rememberScrollState()
            val moviesListState = rememberLazyListState()
            val pickerListState = rememberLazyListState()
            BingeMoteTheme {
                when (current.value) {
                    "setup" -> SetupScreen(
                        remote,
                        setupScroll,
                        onDone = { remote.start(); current.value = "main" },
                        onCancel = if (store.setupDone) ({ current.value = "settings" }) else null,
                    )
                    "mouse" -> MouseScreen(remote) { current.value = "main" }
                    "movies" -> MoviesScreen(remote, moviesListState) { current.value = "main" }
                    "settings" -> SettingsScreen(
                        remote,
                        settingsScroll,
                        pickerListState,
                        onBack = { current.value = "main" },
                        onRepair = { current.value = "setup" },
                        onFeedback = { current.value = "feedback" },
                    )
                    "feedback" -> FeedbackScreen(feedbackScroll) { current.value = "settings" }
                    else -> MainScreen(
                        remote,
                        onMouse = { current.value = "mouse" },
                        onMovies = { current.value = "movies" },
                        onSettings = { current.value = "settings" },
                    )
                }
                UpdateHost(store)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::remote.isInitialized && store.setupDone) remote.start()
    }

    override fun onStop() {
        if (::remote.isInitialized) remote.stop()
        super.onStop()
    }

    /** Volume buttons: steer the movie list there, control VLC volume everywhere else. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::remote.isInitialized || !store.setupDone) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (screen?.value == "movies") MoviesKeyHandler.onKey(MoviesKeyHandler.Key.UP)
                else remote.volumeStep(true)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (screen?.value == "movies") MoviesKeyHandler.onKey(MoviesKeyHandler.Key.DOWN)
                else remote.volumeStep(false)
                true
            }
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
                    updateUi = UpdateUi.Failed("Allow BingeMote to install updates, then tap Update again.")
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
                        Spacer(Modifier.height(8.dp))
                        Text("Android will ask you to confirm the install.", color = Muted)
                        Spacer(Modifier.height(4.dp))
                        TextButton({ store.skippedUpdate = u.info.versionName; updateUi = null }) {
                            Text("Skip this version", color = Orange)
                        }
                    }
                },
                confirmButton = { TextButton({ startUpdate(u.info) }) { Text("Update", color = Orange) } },
                dismissButton = { TextButton({ updateUi = null }) { Text("Later", color = Muted) } },
            )
            is UpdateUi.Downloading -> AlertDialog(
                onDismissRequest = {},
                containerColor = Card2,
                title = { Text("Downloading ${u.info.versionName}", color = TextC) },
                text = {
                    LinearProgressIndicator(
                        progress = { u.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Orange,
                        trackColor = Card,
                    )
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
