package com.github.jkrishna289.orcax.ui.detail

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.BuildConfig
import com.github.jkrishna289.orcax.MainActivity
import com.github.jkrishna289.orcax.data.ItemPlaybackDao
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.ItemPlayback
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.torrent.TorrentPlaybackArgs
import com.github.jkrishna289.orcax.ui.detail.discover.SourcePickerDialog
import com.github.jkrishna289.orcax.ui.detail.discover.SourceStreamingController
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.showToast
import com.github.jkrishna289.orcax.util.ExceptionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.util.versionCodeLong
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DebugViewModel
    @Inject
    constructor(
        val serverRepository: ServerRepository,
        val itemPlaybackDao: ItemPlaybackDao,
        val clientInfo: ClientInfo,
        val deviceInfo: DeviceInfo,
        val navigationManager: NavigationManager,
        val orcaEngineClient: OrcaEngineClient,
        sourceStreamingController: SourceStreamingController,
    ) : ViewModel() {
        val itemPlaybacks = MutableLiveData<List<ItemPlayback>>(listOf())
        val logcat = MutableLiveData<List<LogcatLine>>(listOf())

        /**
         * The real find-a-stream flow, driven from here.
         *
         * [playFromRealSources] proves the *pipeline* and shows no UI at all. This drives the same
         * controller the details screen uses, so the picker, the wait and the escalation ladder are
         * the shipped composables with real search results in them — the only way to exercise those
         * screens on a server with no Jellyseerr, which is what gates the product entry point.
         */
        val sources: SourceStreamingController = sourceStreamingController

        fun findSourcesWithUi() {
            sources.attach(viewModelScope, title = TEST_TITLE, year = TEST_YEAR)
            sources.findSources()
        }

        /**
         * Opens a torrent stream on the engine and hands it to the normal player — the end-to-end
         * check that a torrent source plays through the existing playback stack.
         *
         * Lives on the debug screen on purpose: it proves the pipeline without touching any product
         * surface, so a failure here can't be confused with a UI problem.
         */
        fun playTestMagnet(context: Context) {
            viewModelScope.launchIO {
                withContext(Dispatchers.Main) {
                    // Fetching torrent metadata from peers takes a while, and the button would
                    // otherwise look dead for up to a minute.
                    showToast(context, "Opening stream, this can take a minute...", Toast.LENGTH_LONG)
                }

                val stream = orcaEngineClient.openStreamSession(magnet = TEST_MAGNET)
                if (stream == null) {
                    withContext(Dispatchers.Main) {
                        showToast(
                            context,
                            "No stream. Check that source streaming is enabled in the Orca Engine settings.",
                            Toast.LENGTH_LONG,
                        )
                    }
                    return@launchIO
                }

                Timber.i(
                    "Test magnet stream ready: %s (%d bytes, %d tracks)",
                    stream.fileName,
                    stream.sizeBytes ?: -1,
                    stream.mediaInfo?.streams?.size ?: 0,
                )
                withContext(Dispatchers.Main) {
                    navigationManager.navigateTo(
                        Destination.Playback(
                            // Throwaway id: there is no Jellyfin item, and a fresh one each time is
                            // what keeps this out of the resume/next-up tables.
                            itemId = UUID.randomUUID(),
                            positionMs = 0,
                            torrent =
                                TorrentPlaybackArgs(
                                    url = stream.url,
                                    title = "Sintel (test stream)",
                                    fileName = stream.fileName,
                                    sizeBytes = stream.sizeBytes,
                                    mediaInfo = stream.mediaInfo,
                                    token = stream.token,
                                ),
                        ),
                    )
                }
            }
        }

        val supportedModes by lazy {
            val displayManager =
                MainActivity.instance.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            display.supportedModes.orEmpty()
        }

        val av1Included by lazy {
            try {
                Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

        val ffmpegIncluded by lazy {
            try {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

        val libMpvLoaded by lazy {
            try {
                System.loadLibrary("player")
                System.loadLibrary("mpv")
                true
            } catch (_: Throwable) {
                // UnsatisfiedLinkError (an Error, not Exception) when the MPV native lib is absent.
                false
            }
        }

        init {
            viewModelScope.launchIO {
                serverRepository.currentUser.value?.rowId?.let {
                    val results = itemPlaybackDao.getItems(it)
                    withContext(Dispatchers.Main) {
                        itemPlaybacks.value = results
                    }
                }
                val logcat = getLogCatLines()
                withContext(Dispatchers.Main) {
                    this@DebugViewModel.logcat.value = logcat
                }
            }
        }

        /**
         * Searches real indexers, takes the recommended pick, and plays it.
         *
         * Distinct from [playTestMagnet] in the part that matters: this goes through the whole
         * discovery chain — Prowlarr search, server-side ranking, source-id resolution, and the
         * **.torrent fetch**. Prowlarr almost always returns .torrent proxy links rather than magnets,
         * so this is the path every real source-pick takes, and the hardcoded-magnet button never
         * touches it.
         */
        fun playFromRealSources(context: Context) {
            viewModelScope.launchIO {
                withContext(Dispatchers.Main) {
                    showToast(context, "Searching sources for $TEST_TITLE...", Toast.LENGTH_LONG)
                }

                val groups = orcaEngineClient.getSources(title = TEST_TITLE, year = TEST_YEAR)
                if (groups == null) {
                    withContext(Dispatchers.Main) {
                        showToast(
                            context,
                            "No source search. Check source streaming is on and Prowlarr is configured.",
                            Toast.LENGTH_LONG,
                        )
                    }
                    return@launchIO
                }

                val pick = groups.recommended ?: groups.all.firstOrNull()
                if (pick == null) {
                    // Logged as well as toasted: a toast has faded by the time logcat is read, and
                    // "search returned zero" is indistinguishable from "the button did nothing"
                    // without this line.
                    Timber.w("Source search for %s returned no usable sources", TEST_TITLE)
                    withContext(Dispatchers.Main) {
                        showToast(context, "Search ran but found no usable sources.", Toast.LENGTH_LONG)
                    }
                    return@launchIO
                }

                Timber.i(
                    "Source pick: %s | %s | id=%s seeders=%d indexer=%s",
                    pick.title,
                    pick.summary,
                    pick.id,
                    pick.seeders,
                    pick.indexer ?: "?",
                )
                withContext(Dispatchers.Main) {
                    showToast(context, "Opening ${pick.summary}...", Toast.LENGTH_LONG)
                }

                val stream = orcaEngineClient.openStreamSession(sourceId = pick.id)
                if (stream == null) {
                    withContext(Dispatchers.Main) {
                        showToast(context, "Found sources but couldn't open a stream.", Toast.LENGTH_LONG)
                    }
                    return@launchIO
                }

                Timber.i(
                    "Real-source stream ready: %s (%d bytes, %d tracks)",
                    stream.fileName,
                    stream.sizeBytes ?: -1,
                    stream.mediaInfo?.streams?.size ?: 0,
                )
                withContext(Dispatchers.Main) {
                    navigationManager.navigateTo(
                        Destination.Playback(
                            itemId = UUID.randomUUID(),
                            positionMs = 0,
                            torrent =
                                TorrentPlaybackArgs(
                                    url = stream.url,
                                    title = "$TEST_TITLE (from sources)",
                                    fileName = stream.fileName,
                                    sizeBytes = stream.sizeBytes,
                                    mediaInfo = stream.mediaInfo,
                                    token = stream.token,
                                ),
                        ),
                    )
                }
            }
        }

        companion object {
            // Creative Commons, and already confirmed to return results from the configured indexers.
            private const val TEST_TITLE = "Sintel"
            private const val TEST_YEAR = 2010

            /**
             * Sintel, the Blender Foundation's Creative Commons short film — the canonical WebTorrent
             * test torrent. Deliberately a well-seeded, freely licensed release so this button is a
             * plumbing test and nothing else.
             */
            private const val TEST_MAGNET =
                "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel" +
                    "&tr=udp%3A%2F%2Fexplodie.org%3A6969" +
                    "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337" +
                    "&tr=udp%3A%2F%2Ftracker.empire-js.us%3A1337" +
                    "&tr=udp%3A%2F%2Fopen.demonii.com%3A1337"

            fun getLogCatLines(): List<LogcatLine> {
                val lineCount = 500
                val args =
                    buildList {
                        add("logcat")
                        add("-d")
                        add("-t")
                        add(lineCount.toString())
                        addAll(THIRD_PARTY_TAGS)
                        add("*:V")
                    }
                val process = ProcessBuilder().command(args).redirectErrorStream(true).start()
                val logLines = mutableListOf<LogcatLine>()
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var count = 0

                    while (count < lineCount) {
                        val line = reader.readLine()
                        if (line != null) {
                            val level = line.split(Regex("\\s+")).getOrNull(4)
                            val logLevel =
                                when (level?.uppercase()) {
                                    "V" -> Log.VERBOSE
                                    "D" -> Log.DEBUG
                                    "I" -> Log.INFO
                                    "W" -> Log.WARN
                                    "E" -> Log.ERROR
                                    else -> Log.VERBOSE
                                }
                            logLines.add(LogcatLine(logLevel, line))
                        } else {
                            break
                        }
                        count++
                    }
                } finally {
                    process.destroy()
                }
                return logLines
            }

            fun ViewModel.sendAppLogs(
                context: Context,
                api: ApiClient,
                clientInfo: ClientInfo?,
                deviceInfo: DeviceInfo?,
            ) {
                viewModelScope.launchIO(ExceptionHandler(true)) {
                    val logcat = getLogCatLines().joinToString("\n") { it.text }
                    val body =
                        """
                        Send App Logs
                        clientInfo=$clientInfo
                        deviceInfo=$deviceInfo
                        manufacturer=${Build.MANUFACTURER}
                        model=${Build.MODEL}
                        apiLevel=${Build.VERSION.SDK_INT}

                        """.trimIndent()
                    Timber.w(body)
                    val response by api.clientLogApi.logFile(body + logcat)
                    showToast(context, "Sent! Filename=${response.fileName}")
                }
            }
        }
    }

data class LogcatLine(
    val level: Int,
    val text: String,
)

@Composable
fun DebugPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scrollAmount = 100f
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun scroll(reverse: Boolean = false) {
        scope.launch(ExceptionHandler()) {
            columnState.scrollBy(if (reverse) -scrollAmount else scrollAmount)
        }
    }

    val itemPlaybacks by viewModel.itemPlaybacks.observeAsState(listOf())
    val logcat by viewModel.logcat.observeAsState(listOf())
    val sourceSearch by viewModel.sources.state.collectAsState()

    // The shipped picker surfaces, hosted here exactly as the details screen hosts them.
    SourcePickerDialog(
        state = sourceSearch,
        title = viewModel.sources.title,
        onPick = { viewModel.sources.playSource(it) },
        onRetry = viewModel.sources::retry,
        // There is no request queue to fall back to from the debug screen, so the request route
        // simply closes the flow rather than pretending to queue something.
        onRequest = viewModel.sources::dismiss,
        onNextStream = { viewModel.sources.nextStream() },
        onChooseAnother = viewModel.sources::chooseAnother,
        onKeepWaiting = viewModel.sources::keepWaiting,
        onDismiss = viewModel.sources::dismiss,
    )

    LazyColumn(
        state = columnState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        modifier =
            modifier
                .focusable()
                .background(
                    MaterialTheme.colorScheme.surface,
                ).onKeyEvent {
                    if (it.type == KeyEventType.KeyUp) {
                        return@onKeyEvent false
                    }
                    if (it.key == Key.DirectionDown) {
                        scroll(false)
                        return@onKeyEvent true
                    }
                    if (it.key == Key.DirectionUp) {
                        scroll(true)
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                },
    ) {
        item {
            androidx.tv.material3.Button(
                onClick = {
                    viewModel.navigationManager.navigateTo(Destination.EngineCards)
                },
            ) {
                Text(text = "Open Orca Engine Cards")
            }
        }
        item {
            androidx.tv.material3.Button(
                onClick = { viewModel.playTestMagnet(context) },
            ) {
                Text(text = "Play test torrent stream (Sintel)")
            }
        }
        item {
            androidx.tv.material3.Button(
                onClick = { viewModel.playFromRealSources(context) },
            ) {
                Text(text = "Search sources and play best (Sintel)")
            }
        }
        item {
            androidx.tv.material3.Button(
                onClick = { viewModel.findSourcesWithUi() },
            ) {
                Text(text = "Find sources — show the picker UI (Sintel)")
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "App Information",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val buildTime = Date(BuildConfig.BUILD_TIME)
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val installInfo =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val installSource =
                            context.packageManager.getInstallSourceInfo(context.packageName)
                        buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add("Install source: ${installSource.packageSource}")
                            }
                            add("Installer: ${installSource.installingPackageName}")
                            add("Initiator: ${installSource.initiatingPackageName}")
                        }
                    } else {
                        listOf(context.packageManager.getInstallerPackageName(context.packageName))
                    }
                (
                    listOf(
                        "Version Name: ${pkgInfo.versionName}",
                        "Version Code: ${pkgInfo.versionCodeLong}",
                        "ClientInfo:  ${viewModel.clientInfo}",
                        "Build type: ${BuildConfig.BUILD_TYPE}",
                        "Build flavor: ${BuildConfig.FLAVOR}",
                        "Build time: $buildTime",
                        "FFMPEG included: ${viewModel.ffmpegIncluded}",
                        "AV1 included: ${viewModel.av1Included}",
                        "libmpv loaded: ${viewModel.libMpvLoaded}",
                        "Debug enabled: ${BuildConfig.DEBUG}",
                        "ABIs: ${Build.SUPPORTED_ABIS.toList()}",
                    ) + installInfo
                ).forEach {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Device Information",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                listOf(
                    "DeviceInfo:  ${viewModel.deviceInfo}",
                    "Manufacturer: ${Build.MANUFACTURER}",
                    "Model: ${Build.MODEL}",
                    "API Level: ${Build.VERSION.SDK_INT}",
                    "Display Modes:",
                    *viewModel.supportedModes,
                ).forEach {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "AppPreferences",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = preferences.appPreferences.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "User Information",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Current server: ${viewModel.serverRepository.currentServer.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Current user: ${viewModel.serverRepository.currentUser.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "User server settings: ${viewModel.serverRepository.currentUserDto.value?.configuration}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Database",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "ItemPlayback",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                itemPlaybacks.forEach {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Logcat",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                logcat.forEach { (level, line) ->
                    val color =
                        when (level) {
                            Log.VERBOSE -> MaterialTheme.colorScheme.onSurface
                            Log.DEBUG -> Color(0xff2bc4cf)
                            Log.INFO -> Color(0xff2bcf8b)
                            Log.WARN -> Color(0xffdde663)
                            Log.ERROR -> Color(0xffe67063)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
        }
    }
}

private val THIRD_PARTY_TAGS =
    listOf(
        "libc:F",
        "ExoPlayerImpl:W",
        // FireTV
        "Codec2Client:E",
        "CCodecBuffers:E",
        "CCodecConfig:E",
        "okhttp.Http2:W",
        "okhttp.TaskRunner:W",
        "LruBitmapPool:W",
        "FragmentManager:W",
        "ConfigStore:W",
        "GlideRequest:W",
        "FactoryPools:W",
        "ViewTarget:W",
        "Engine:W",
        "Downsampler:W",
        "TransformationUtils:W",
        "DecodeJob:W",
        "BufferPoolAccessor2.0:W",
        "ExifInterface:W",
        "MediaCodec:W",
        "SurfaceUtils:W",
        "ByteArrayPool:W",
        "HardwareConfig:W",
        "DfltImageHeaderParser:W",
    )
