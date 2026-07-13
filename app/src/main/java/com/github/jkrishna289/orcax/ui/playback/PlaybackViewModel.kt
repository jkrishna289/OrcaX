package com.github.jkrishna289.orcax.ui.playback

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.ui.text.intl.Locale
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import com.github.jkrishna289.orcax.data.ItemPlaybackDao
import com.github.jkrishna289.orcax.data.ItemPlaybackRepository
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.Chapter
import com.github.jkrishna289.orcax.data.model.ItemPlayback
import com.github.jkrishna289.orcax.data.model.Playlist
import com.github.jkrishna289.orcax.data.model.TrackIndex
import com.github.jkrishna289.orcax.engine.ContentWarningsResponse
import com.github.jkrishna289.orcax.preferences.AppPreference
import com.github.jkrishna289.orcax.preferences.PlayerBackend
import com.github.jkrishna289.orcax.preferences.ShowNextUpWhen
import com.github.jkrishna289.orcax.preferences.SkipSegmentBehavior
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.DatePlayedService
import com.github.jkrishna289.orcax.services.DeviceProfileService
import com.github.jkrishna289.orcax.services.ImageUrlService
import com.github.jkrishna289.orcax.services.MusicService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.PlayerFactory
import com.github.jkrishna289.orcax.services.PlaylistCreationResult
import com.github.jkrishna289.orcax.services.PlaylistCreator
import com.github.jkrishna289.orcax.services.RefreshRateService
import com.github.jkrishna289.orcax.services.ScreensaverService
import com.github.jkrishna289.orcax.services.StreamChoiceService
import com.github.jkrishna289.orcax.services.UserPreferencesService
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.QualityPreferenceDao
import com.github.jkrishna289.orcax.services.hilt.StandardOkHttpClient
import com.github.jkrishna289.orcax.ui.playback.quality.DeviceAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.NetworkAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.PlaybackHealthTracker
import com.github.jkrishna289.orcax.ui.playback.quality.QualityResolver
import com.github.jkrishna289.orcax.ui.playback.quality.QualitySelection
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.onMain
import com.github.jkrishna289.orcax.ui.seekBack
import com.github.jkrishna289.orcax.ui.seekForward
import com.github.jkrishna289.orcax.ui.setValueOnMain
import com.github.jkrishna289.orcax.ui.showToast
import com.github.jkrishna289.orcax.ui.toServerString
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.LoadingState
import com.github.jkrishna289.orcax.util.PlaybackItemState
import com.github.jkrishna289.orcax.util.TrackActivityPlaybackListener
import com.github.jkrishna289.orcax.util.checkForSupport
import com.github.jkrishna289.orcax.util.mpv.mpvDeviceProfile
import com.github.jkrishna289.orcax.util.profile.Codec
import com.github.jkrishna289.orcax.util.subtitleMimeTypes
import com.github.jkrishna289.orcax.util.supportItemKinds
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.peerless2012.ass.media.AssHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.TrickplayInfo
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * This [ViewModel] is responsible for playing media including moving through playlists (including next up episodes)
 */
@HiltViewModel(assistedFactory = PlaybackViewModel.Factory::class)
@OptIn(markerClass = [UnstableApi::class])
class PlaybackViewModel
@AssistedInject
constructor(
    @param:ApplicationContext internal val context: Context,
    internal val api: ApiClient,
    val navigationManager: NavigationManager,
    private val playlistCreator: PlaylistCreator,
    private val itemPlaybackDao: ItemPlaybackDao,
    private val serverRepository: ServerRepository,
    private val itemPlaybackRepository: ItemPlaybackRepository,
    private val playerFactory: PlayerFactory,
    private val datePlayedService: DatePlayedService,
    private val deviceInfo: DeviceInfo,
    private val deviceProfileService: DeviceProfileService,
    private val refreshRateService: RefreshRateService,
    val streamChoiceService: StreamChoiceService,
    private val userPreferencesService: UserPreferencesService,
    private val imageUrlService: ImageUrlService,
    private val screensaverService: ScreensaverService,
    @StandardOkHttpClient private val okHttpClient: OkHttpClient,
    private val musicService: MusicService,
    private val engineClient: OrcaEngineClient,
    private val networkAnalyzer: NetworkAnalyzer,
    private val deviceAnalyzer: DeviceAnalyzer,
    private val qualityPreferenceDao: QualityPreferenceDao,
    @Assisted private val destination: Destination,
) : ViewModel(),
    Player.Listener,
    AnalyticsListener {
    @AssistedFactory
    interface Factory {
        fun create(destination: Destination): PlaybackViewModel
    }

    val currentPlayer = MutableStateFlow<PlayerState?>(null)

    private val _positionState = MutableStateFlow(PlaybackPositionState(0L, 0L, 0L))
    val positionState: StateFlow<PlaybackPositionState> = _positionState.asStateFlow()
    private var positionPollingJob: Job? = null

    // ── Quality selection manager ──────────────────────────────────────────
    val qualityManager = QualityManager(networkAnalyzer, qualityPreferenceDao, viewModelScope)

    // True while a stream switch (quality/audio/subtitle change) is in progress.
    // PlaybackPage shows a black cover whenever this is true, preventing the
    // dual-video glitch that occurs when a transcoded stream has different
    // dimensions from the previous one (old SurfaceView frame shows behind
    // the resized PlayerSurface). Reset in onRenderedFirstFrame().
    val isSwitchingStream = MutableStateFlow(true)

    internal lateinit var player: Player

    private var mediaSession: MediaSession? = null
    internal val mutex = Mutex()

    val controllerViewState =
        ControllerViewState(
            AppPreference.ControllerTimeout.defaultValue,
            true,
        )

    val loading = MutableLiveData<LoadingState>(LoadingState.Loading)

    val currentMediaInfo = MutableLiveData<CurrentMediaInfo>(CurrentMediaInfo.EMPTY)
    val currentPlayback = MutableStateFlow<CurrentPlayback?>(null)
    val currentItemPlayback = MutableLiveData<ItemPlayback>()
    val currentSegment = MutableStateFlow<MediaSegmentState?>(null)

    // Passive content advisory shown briefly at the start of playback (null = hidden). Generated by the
    // engine (Groq) per movie/series and fetched once per subject; never grabs focus.
    val contentWarning = MutableStateFlow<ContentWarningsResponse?>(null)
    private var warningShownForSubjectId: UUID? = null

    val subtitleCues = MutableLiveData<List<Cue>>(listOf())

    private lateinit var preferences: UserPreferences
    internal lateinit var itemId: UUID
    internal lateinit var item: BaseItem
    internal var forceTranscoding: Boolean = false
    private var activityListener: TrackActivityPlaybackListener? = null
    private val jobs = mutableListOf<Job>()

    val nextUp = MutableLiveData<BaseItem?>()
    private val isPlaylist = destination is Destination.PlaybackList

    val playlist = MutableLiveData<Playlist>(Playlist(listOf()))
    val subtitleSearchStatus = MutableLiveData<SubtitleSearchStatus?>(null)
    val subtitleSearchLanguage = MutableLiveData<String>(Locale.current.language)

    val currentUserDto = serverRepository.currentUserDto

    init {
        viewModelScope.launchIO {
            addCloseable {
                screensaverService.keepScreenOn(false)
                disconnectPlayer()
            }
            init()
        }
        // Quality engine events: rescue rebuilds + informational toasts.
        viewModelScope.launch {
            qualityManager.events.collect { event ->
                when (event) {
                    is QualityEvent.RescueDowngrade -> {
                        showToast(
                            context,
                            context.getString(
                                R.string.quality_rescue_toast,
                                rungLabel(event.rung),
                            ),
                            Toast.LENGTH_LONG,
                        )
                        val item = currentPlayback.value?.item ?: return@collect
                        val playback = currentItemPlayback.value ?: return@collect
                        changeStreams(
                            item = item,
                            audioIndex = playback.audioIndex,
                            subtitleIndex = playback.subtitleIndex,
                            positionMs = onMain { player.currentPosition },
                            userInitiated = false,
                            enableDirectPlay = false,
                            enableDirectStream = true,
                            allowAudioStreamCopy = qualityManager.effectiveAllowAudioStreamCopy,
                            forceRebuild = true,
                        )
                    }
                    QualityEvent.ManualStruggling ->
                        showToast(
                            context,
                            context.getString(R.string.quality_manual_struggling_toast),
                            Toast.LENGTH_LONG,
                        )
                    is QualityEvent.StartedReduced ->
                        showToast(
                            context,
                            context.getString(
                                R.string.quality_started_reduced_toast,
                                rungLabel(event.rung),
                                event.measuredBps?.let { mbpsLabel(it) } ?: "?",
                            ),
                            Toast.LENGTH_LONG,
                        )
                }
            }
        }
    }

    private fun rungLabel(rung: com.github.jkrishna289.orcax.ui.playback.quality.QualityRung): String =
        mbpsLabel(rung.maxBitrateBps.toLong())

    private fun mbpsLabel(bps: Long): String {
        val mbps = bps / 1_000_000.0
        return if (mbps >= 10) "${mbps.toInt()} Mbps" else String.format("%.1f Mbps", mbps)
    }

    private fun disconnectPlayer() {
        positionPollingJob?.cancel()
        positionPollingJob = null
        if (this@PlaybackViewModel::player.isInitialized) {
            player.removeListener(this@PlaybackViewModel)
            (player as? ExoPlayer)?.removeAnalyticsListener(this@PlaybackViewModel)

            this@PlaybackViewModel.activityListener?.let {
                it.release()
                player.removeListener(it)
            }
            player.release()
            mediaSession?.release()
        }
        jobs.forEach { it.cancel() }
    }

    private suspend fun createPlayer(
        isHdr: Boolean,
        is4k: Boolean,
    ) {
        val softwareDecoding =
            !preferences.appPreferences.playbackPreferences.mpvOptions.enableHardwareDecoding
        val playerBackend =
            when (preferences.appPreferences.playbackPreferences.playerBackend) {
                PlayerBackend.UNRECOGNIZED,
                PlayerBackend.EXO_PLAYER,
                    -> PlayerBackend.EXO_PLAYER

                PlayerBackend.MPV -> PlayerBackend.MPV

                PlayerBackend.PREFER_MPV -> if (isHdr || (is4k && softwareDecoding)) PlayerBackend.EXO_PLAYER else PlayerBackend.MPV

                PlayerBackend.EXTERNAL_PLAYER -> throw IllegalStateException("Cannot use this for external playback")
            }

        Timber.d("Selected backend: %s", playerBackend)
        if (currentPlayer.value?.backend != playerBackend) {
            Timber.i("Switching player backend to %s", playerBackend)
            withContext(Dispatchers.Main) {
                disconnectPlayer()
            }

            val playerCreation =
                playerFactory.createVideoPlayer(
                    playerBackend,
                    preferences.appPreferences.playbackPreferences,
                )
            this.player = playerCreation.player
            currentPlayer.update {
                PlayerState(playerCreation.player, playerBackend, playerCreation.assHandler)
            }
            configurePlayer()
        }
    }

    private fun configurePlayer() {
        player.addListener(this)
        (player as? ExoPlayer)?.addAnalyticsListener(this)
        jobs.add(subscribe())
        jobs.add(listenForTranscodeReason())
        val sessionPlayer =
            MediaSessionPlayer(
                player,
                preferences.appPreferences.playbackPreferences,
            )
        mediaSession =
            MediaSession
                .Builder(context, sessionPlayer)
                .build()
    }

    /**
     * Initialize from the UI to start playback
     */
    private suspend fun init() {
        musicService.stop()
        nextUp.setValueOnMain(null)
        this.preferences = userPreferencesService.getCurrent()
        if (preferences.appPreferences.playbackPreferences.refreshRateSwitching) {
            addCloseable { refreshRateService.resetRefreshRate() }
        }
        controllerViewState.hideMilliseconds =
            preferences.appPreferences.playbackPreferences.controllerTimeoutMs
        this.forceTranscoding =
            (destination as? Destination.Playback)?.forceTranscoding ?: false
        val positionMs: Long
        val itemPlayback: ItemPlayback?
        val forceTranscoding: Boolean

        val itemId =
            when (val d = destination) {
                is Destination.Playback -> {
                    positionMs = d.positionMs
                    itemPlayback = d.itemPlayback
                    forceTranscoding = d.forceTranscoding
                    d.itemId
                }

                is Destination.PlaybackList -> {
                    positionMs = 0
                    itemPlayback = null
                    forceTranscoding = false
                    d.itemId
                }

                else -> {
                    throw IllegalArgumentException("Destination not supported: $destination")
                }
            }
        this.itemId = itemId
        val queriedItem = api.userLibraryApi.getItem(itemId).content
        val base =
            if (queriedItem.type.playable) {
                queriedItem
            } else if (destination is Destination.PlaybackList) {
                val playlistResult =
                    playlistCreator.createFrom(
                        item = queriedItem,
                        startIndex = destination.startIndex ?: 0,
                        sortAndDirection = destination.sortAndDirection,
                        shuffled = destination.shuffle,
                        recursive = destination.recursive,
                        filter = destination.filter,
                    )
                when (val r = playlistResult) {
                    is PlaylistCreationResult.Error -> {
                        loading.setValueOnMain(LoadingState.Error(r.message, r.ex))
                        return
                    }

                    is PlaylistCreationResult.Success -> {
                        if (r.playlist.items.isEmpty()) {
                            showToast(context, "Playlist is empty", Toast.LENGTH_SHORT)
                            navigationManager.goBack()
                            return
                        }
                        if (preferences.appPreferences.playbackPreferences.showNextUpWhen != ShowNextUpWhen.NEXT_UP_NEVER) {
                            withContext(Dispatchers.Main) {
                                this@PlaybackViewModel.playlist.value = r.playlist
                            }
                        }
                        r.playlist.items
                            .first()
                            .data
                    }
                }
            } else {
                throw IllegalArgumentException("Item is not playable and not PlaybackList: ${queriedItem.type}")
            }

        viewModelScope.launch(ExceptionHandler()) { controllerViewState.observe() }

        val item = BaseItem.from(base, api)
        val played =
            play(
                item,
                positionMs,
                itemPlayback,
                forceTranscoding,
            )
        if (!played) {
            playNextUp()
        }

        if (!isPlaylist && preferences.appPreferences.playbackPreferences.showNextUpWhen != ShowNextUpWhen.NEXT_UP_NEVER) {
            val result = playlistCreator.createFrom(queriedItem)
            if (result is PlaylistCreationResult.Success && result.playlist.items.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    this@PlaybackViewModel.playlist.value = result.playlist
                }
            }
        }
    }

    /**
     * Play an item
     *
     * @param item the item to play
     * @param positionMs the starting playback position in milliseconds
     * @param itemPlayback the parameters for playback such chosen subtitle or audio streams
     * @param forceTranscoding whether the user has requested to force playback via transcoding
     */
    private suspend fun play(
        item: BaseItem,
        positionMs: Long,
        itemPlayback: ItemPlayback? = null,
        forceTranscoding: Boolean = this.forceTranscoding,
    ): Boolean =
        withContext(Dispatchers.IO) {
            Timber.i("Playing ${item.id}")

            // New item, so we can clear the media segment tracker & subtitle cues
            resetSegmentState()
            this@PlaybackViewModel.subtitleCues.setValueOnMain(listOf())

            viewModelScope.launchIO {
                // Starting playback, so want to invalidate the last played timestamp for this item
                datePlayedService.invalidate(item)
            }

            if (item.type !in supportItemKinds) {
                showToast(
                    context,
                    "Unsupported type '${item.type}', skipping...",
                    Toast.LENGTH_SHORT,
                )
                return@withContext false
            }
            this@PlaybackViewModel.item = item
            this@PlaybackViewModel.itemId = item.id

            // ── Quality engine: new item ──────────────────────────────────
            // Synchronous state reset + persisted manual pick load BEFORE the
            // stream builds (kills the stale-tier race). AUTO starts at
            // Original; the rescue engine downgrades only on observed
            // starvation — there is no post-resolve re-prepare anymore.
            qualityManager.onNewItem(
                seriesId = item.data.seriesId,
                itemId = item.id,
                userRowId = serverRepository.currentUser.value?.rowId,
                userId = serverRepository.currentUser.value?.id?.toString(),
            )

            val isLiveTv = item.type == BaseItemKind.TV_CHANNEL
            val base = item.data

            // Use the provided playback parameters or else check if the database has some
            val playbackConfig =
                itemPlayback
                    ?: serverRepository.currentUser.value?.let { user ->
                        itemPlaybackDao.getItem(user, base.id)?.let {
                            Timber.v("Fetched itemPlayback from DB: %s", it)
                            if (it.sourceId != null) {
                                it
                            } else {
                                null
                            }
                        }
                    }
            val mediaSource = streamChoiceService.chooseSource(base, playbackConfig)
            val plc = streamChoiceService.getPlaybackLanguageChoice(base)

            if (mediaSource == null) {
                showToast(
                    context,
                    "Item has no media sources, skipping...",
                    Toast.LENGTH_SHORT,
                )
                return@withContext false
            }

            val videoStream =
                mediaSource.mediaStreams
                    ?.firstOrNull { it.type == MediaStreamType.VIDEO }
                    ?.let {
                        val isHdr =
                            it.videoRange == VideoRange.HDR ||
                                    (it.videoRangeType != VideoRangeType.SDR && it.videoRangeType != VideoRangeType.UNKNOWN)
                        val is4k = (it.width ?: 0) > 2560 || (it.height ?: 0) > 1440
                        SimpleVideoStream(
                            index = it.index,
                            hdr = isHdr,
                            is4k = is4k,
                            width = it.width ?: 0,
                            height = it.height ?: 0,
                            bitrateBps = it.bitRate ?: 0,
                            codec = it.codec,
                        )
                    }

            // Create the correct player for the media
            createPlayer(videoStream?.hdr == true, videoStream?.is4k == true)
            val subtitleLanguagePreference =
                serverRepository.currentUserDto.value
                    ?.configuration
                    ?.subtitleLanguagePreference
            val subtitleStreams =
                mediaSource.mediaStreams
                    ?.filter { it.type == MediaStreamType.SUBTITLE }
                    .let {
                        if (subtitleLanguagePreference.isNotNullOrBlank()) {
                            it?.sortedByDescending { it.language != null && subtitleLanguagePreference == it.language }
                        } else {
                            it
                        }
                    }?.map {
                        SimpleMediaStream.from(context, it, true)
                    }.orEmpty()

            val audioStreams =
                mediaSource.mediaStreams
                    ?.filter { it.type == MediaStreamType.AUDIO }
                    ?.map {
                        SimpleMediaStream.from(context, it, true)
                    }
                    .orEmpty()
            val audioStream =
                streamChoiceService
                    .chooseAudioStream(
                        source = mediaSource,
                        seriesId = base.seriesId,
                        itemPlayback = playbackConfig,
                        plc = plc,
                        prefs = preferences,
                    )
            val audioIndex = audioStream?.index

            // ── Quality engine: media + device profile ────────────────────
            // Profiles the source StreamChoiceService actually picked. Decides
            // the start selection synchronously from the CACHED measurement
            // (warm-cache pre-empt / audio-compat guard) before the first
            // stream build; a background probe refines the picker only.
            qualityManager.onMediaProfile(
                QualityResolver.MediaQualityProfile(
                    videoBitrateBps = videoStream?.bitrateBps ?: 0,
                    audioBitrateBps = audioStream?.bitRate ?: 0,
                    width = videoStream?.width ?: 0,
                    height = videoStream?.height ?: 0,
                    isHdr = videoStream?.hdr == true,
                    videoCodec = videoStream?.codec,
                    audioCodec = audioStream?.codec,
                    audioChannels = audioStream?.channels,
                ),
                deviceAnalyzer.audioCaps(preferences.appPreferences),
            )

            val subtitleIndex =
                streamChoiceService
                    .chooseSubtitleStream(
                        source = mediaSource,
                        audioStream = audioStream,
                        seriesId = base.seriesId,
                        itemPlayback = playbackConfig,
                        plc = plc,
                        prefs = preferences,
                    )?.index

            Timber.d("Selected mediaSource=${mediaSource.id}, audioIndex=$audioIndex, subtitleIndex=$subtitleIndex")

            val itemPlaybackToUse =
                playbackConfig ?: ItemPlayback(
                    rowId = -1,
                    userId = -1,
                    itemId = base.id,
                    sourceId = if (!isLiveTv) mediaSource.id?.toUUIDOrNull() else null,
                    audioIndex = audioIndex ?: TrackIndex.UNSPECIFIED,
                    subtitleIndex = subtitleIndex ?: TrackIndex.UNSPECIFIED,
                )
            val trickPlayInfo =
                item.data.trickplay
                    ?.get(mediaSource.id)
                    ?.values
                    ?.firstOrNull()
            trickPlayInfo?.let { trickplayInfo ->
                mediaSource.runTimeTicks?.ticks?.let { duration ->
                    viewModelScope.launchIO {
                        prefetchTrickplay(
                            duration,
                            trickplayInfo,
                            mediaSource.id?.toUUIDOrNull(),
                        )
                    }
                }
            }

            val chapters = Chapter.fromDto(base, api)
            withContext(Dispatchers.Main) {
                this@PlaybackViewModel.currentItemPlayback.value = itemPlaybackToUse
                updateCurrentMedia {
                    CurrentMediaInfo(
                        sourceId = mediaSource.id,
                        videoStream = videoStream,
                        audioStreams = audioStreams,
                        subtitleStreams = subtitleStreams,
                        chapters = chapters,
                        trickPlayInfo = trickPlayInfo,
                    )
                }

                changeStreams(
                    item,
                    itemPlaybackToUse,
                    audioIndex,
                    subtitleIndex,
                    if (positionMs > 0) positionMs else C.TIME_UNSET,
                    itemPlayback != null,
                    enableDirectPlay = !forceTranscoding,
                    enableDirectStream = !forceTranscoding,
                )
                player.prepare()
                player.play()
            }
            listenForSegments(item.id)
            return@withContext true
        }

    /**
     * Change which streams (ie audio or subtitle) are active
     *
     * @param forceRebuild skip the in-player direct-play track-switch shortcut and
     * always rebuild the stream via a fresh PlaybackInfo request. Required for
     * quality tier changes and error fallbacks — the shortcut keeps the existing
     * stream playing, which would silently turn those into no-ops.
     */
    @OptIn(UnstableApi::class)
    internal suspend fun changeStreams(
        item: BaseItem,
        currentItemPlayback: ItemPlayback = this@PlaybackViewModel.currentItemPlayback.value!!,
        audioIndex: Int?,
        subtitleIndex: Int?,
        positionMs: Long = 0,
        userInitiated: Boolean,
        enableDirectPlay: Boolean = !this.forceTranscoding,
        enableDirectStream: Boolean = !this.forceTranscoding,
        allowAudioStreamCopy: Boolean = enableDirectStream,
        forceRebuild: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val itemId = item.id

        val currentPlayback = this@PlaybackViewModel.currentPlayback.value
        if (!forceRebuild && currentPlayback != null && currentPlayback.item.id == item.id && currentPlayback.playMethod == PlayMethod.DIRECT_PLAY) {
            val wasSuccessful =
                changeStreamsDirectPlay(
                    currentPlayback = currentPlayback,
                    currentItemPlayback = currentItemPlayback,
                    audioIndex = audioIndex,
                    subtitleIndex = subtitleIndex,
                    userInitiated = userInitiated,
                )
            if (wasSuccessful) return@withContext
        }

        Timber.d(
            "changeStreams: userInitiated=$userInitiated, audioIndex=$audioIndex, subtitleIndex=$subtitleIndex, " +
                    "enableDirectPlay=$enableDirectPlay, enableDirectStream=$enableDirectStream, " +
                    "allowAudioStreamCopy=$allowAudioStreamCopy, forceRebuild=$forceRebuild, positionMs=$positionMs",
        )

        // ── Quality bitrate cap ────────────────────────────────────────────
        // qualityManager.effectiveBitrateBps returns Int.MAX_VALUE while the
        // speed test is still running (so the server can direct-play freely),
        // and the resolved tier's bitrate once the test completes.
        val qualityBitrate = qualityManager.effectiveBitrateBps
        val prefBitrate =
            preferences.appPreferences.playbackPreferences.maxBitrate
                .takeIf { it > 0 } ?: AppPreference.DEFAULT_BITRATE
        // Quality selection wins unless the user's global pref is lower
        val maxBitrate =
            if (qualityBitrate == Int.MAX_VALUE) prefBitrate
            else minOf(qualityBitrate.toLong(), prefBitrate)

        val response by
        api.mediaInfoApi
            .getPostedPlaybackInfo(
                itemId,
                PlaybackInfoDto(
                    startTimeTicks = null,
                    deviceProfile =
                        if (currentPlayer.value!!.backend == PlayerBackend.EXO_PLAYER) {
                            deviceProfileService.getOrCreateDeviceProfile(
                                preferences.appPreferences.playbackPreferences,
                                serverRepository.currentServer.value?.serverVersion,
                            )
                        } else {
                            mpvDeviceProfile
                        },
                    maxAudioChannels = null,
                    audioStreamIndex = audioIndex,
                    subtitleStreamIndex = subtitleIndex,
                    mediaSourceId = currentItemPlayback.sourceId?.toServerString(),
                    alwaysBurnInSubtitleWhenTranscoding = false,
                    maxStreamingBitrate = maxBitrate.toInt(),
                    enableDirectPlay = enableDirectPlay,
                    enableDirectStream = enableDirectStream,
                    allowVideoStreamCopy = enableDirectStream,
                    allowAudioStreamCopy = allowAudioStreamCopy,
                    enableTranscoding = true,
                    autoOpenLiveStream = true,
                ),
            )
        if (response.errorCode != null) {
            loading.setValueOnMain(LoadingState.Error(response.errorCode?.serialName))
            return@withContext
        }
        val source = response.mediaSources.firstOrNull()
        source?.let { source ->
            val mediaUrl =
                if (source.supportsDirectPlay) {
                    if (source.isRemote && source.path.isNotNullOrBlank()) {
                        Timber.i("Playback is remote for source: %s", source.id)
                        source.path
                    } else {
                        api.videosApi.getVideoStreamUrl(
                            itemId = itemId,
                            mediaSourceId = source.id,
                            static = true,
                            tag = source.eTag,
                            playSessionId = response.playSessionId,
                        )
                    }
                } else if (source.supportsDirectStream) {
                    source.transcodingUrl?.let(api::createUrl)
                } else {
                    source.transcodingUrl?.let(api::createUrl)
                }
            if (mediaUrl.isNullOrBlank()) {
                loading.setValueOnMain(
                    LoadingState.Error("Unable to get media URL from the server. Do you have permission to view and/or transcode?"),
                )
                return@withContext
            }
            val transcodeType =
                when {
                    source.supportsDirectPlay -> PlayMethod.DIRECT_PLAY
                    source.supportsDirectStream -> PlayMethod.DIRECT_STREAM
                    source.supportsTranscoding -> PlayMethod.TRANSCODE
                    else -> throw Exception("No supported playback method")
                }
            Timber.i("Playback decision for $itemId: $transcodeType")

            val externalSubtitleCount = source.externalSubtitlesCount

            val externalSubtitle =
                source.findExternalSubtitle(subtitleIndex)?.let {
                    it.deliveryUrl?.let { deliveryUrl ->
                        var flags = 0
                        if (it.isForced) flags = flags.or(C.SELECTION_FLAG_FORCED)
                        if (it.isDefault) flags = flags.or(C.SELECTION_FLAG_DEFAULT)
                        MediaItem.SubtitleConfiguration
                            .Builder(
                                api.createUrl(deliveryUrl).toUri(),
                            ).setId("e:${it.index}")
                            .setMimeType(subtitleMimeTypes[it.codec])
                            .setLanguage(it.language)
                            .setLabel(it.title)
                            .setSelectionFlags(flags)
                            .build()
                    }
                }

            Timber.v("subtitleIndex=$subtitleIndex, externalSubtitleCount=$externalSubtitleCount, externalSubtitle=$externalSubtitle")

            val mediaItem =
                MediaItem
                    .Builder()
                    .setMediaId(itemId.toString())
                    .setMediaMetadata(
                        item.toMediaMetadata(
                            imageUrlService.getItemImageUrl(
                                item,
                                ImageType.PRIMARY,
                                useSeriesForPrimary = true,
                            ),
                        ),
                    ).setUri(mediaUrl.toUri())
                    .setSubtitleConfigurations(listOfNotNull(externalSubtitle))
                    .apply {
                        when (source.container) {
                            Codec.Container.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                            Codec.Container.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                        }
                    }.build()

            val playback =
                CurrentPlayback(
                    item = item,
                    tracks = listOf(),
                    backend = currentPlayer.value!!.backend,
                    playMethod = transcodeType,
                    playSessionId = response.playSessionId,
                    liveStreamId = source.liveStreamId,
                    mediaSourceInfo = source,
                )

            preferences.appPreferences.playbackPreferences.let { prefs ->
                source.mediaStreams
                    ?.firstOrNull { it.type == MediaStreamType.VIDEO }
                    ?.let { stream ->
                        refreshRateService.changeRefreshRate(
                            stream = stream,
                            switchRefreshRate = prefs.refreshRateSwitching,
                            switchResolution = prefs.resolutionSwitching,
                        )
                    }
            }
            withContext(Dispatchers.Main) {
                this@PlaybackViewModel.activityListener?.let {
                    it.release()
                    player.removeListener(it)
                }

                val playbackItemState = PlaybackItemState(playback, currentItemPlayback)
                val activityListener =
                    TrackActivityPlaybackListener(
                        api = api,
                        player = player,
                        getState = { playbackItemState },
                    )
                player.addListener(activityListener)
                this@PlaybackViewModel.activityListener = activityListener

                // Detect play-method change. A DIRECT_PLAY (or DIRECT_STREAM) →
                // TRANSCODE switch uses a different container (MPEG-TS) whose SAR
                // metadata is often wrong. Reusing the same player without a full
                // stop/prepare leaves stale render-pipeline state from the old
                // stream, causing the surface to stay at the old video dimensions.
                val previousMethod = this@PlaybackViewModel.currentPlayback.value?.playMethod
                val needsCleanReset = previousMethod != null
                    && previousMethod != PlayMethod.TRANSCODE
                    && transcodeType == PlayMethod.TRANSCODE
                Timber.i(
                    "[STREAM-SWITCH] %s → %s cleanReset=%b player=#%d",
                    previousMethod?.serialName ?: "none",
                    transcodeType.serialName,
                    needsCleanReset,
                    System.identityHashCode(player),
                )
                if (needsCleanReset) {
                    Timber.i("[STREAM-SWITCH] Stopping player (state=%d) before DP→Transcode reset", player.playbackState)
                    player.stop()
                    Timber.i("[STREAM-SWITCH] Player stopped (state=%d)", player.playbackState)
                }

                isSwitchingStream.update { true }
                loading.value = LoadingState.Success
                this@PlaybackViewModel.currentPlayback.update { playback }

                Timber.i("[STREAM-SWITCH] Setting media item positionMs=%d method=%s", positionMs, transcodeType.serialName)
                player.setMediaItem(
                    mediaItem,
                    positionMs,
                )
                Timber.i("[STREAM-SWITCH] Media item set (player.playbackState=%d)", player.playbackState)
                if (needsCleanReset) {
                    Timber.i("[STREAM-SWITCH] Preparing fresh Transcode session")
                    player.prepare()
                    player.play()
                }

                if (audioIndex != null || subtitleIndex != null) {
                    val onTracksChangedListener =
                        object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) {
                                Timber.v("onTracksChanged: $tracks")
                                if (tracks.groups.isNotEmpty()) {
                                    val result =
                                        TrackSelectionUtils.createTrackSelections(
                                            player.trackSelectionParameters,
                                            player.currentTracks,
                                            currentPlayer.value!!.backend,
                                            source.supportsDirectPlay,
                                            audioIndex.takeIf { transcodeType == PlayMethod.DIRECT_PLAY },
                                            subtitleIndex,
                                            source,
                                        )
                                    if (result.bothSelected) {
                                        player.trackSelectionParameters =
                                            result.trackSelectionParameters
                                        player.removeListener(this)
                                    }
                                    viewModelScope.launchIO { loadSubtitleDelay() }
                                }
                            }
                        }
                    player.addListener(onTracksChangedListener)
                }
            }
        }
    }

    /**
     * If direct playing, can try to switch tracks without playback restarting
     * Except for external subtitles
     */
    @OptIn(UnstableApi::class)
    private suspend fun changeStreamsDirectPlay(
        currentPlayback: CurrentPlayback,
        currentItemPlayback: ItemPlayback,
        audioIndex: Int?,
        subtitleIndex: Int?,
        userInitiated: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            Timber.v("changeStreams direct play")

            val source = currentPlayback.mediaSourceInfo
            val externalSubtitle = source.findExternalSubtitle(subtitleIndex)

            if (externalSubtitle == null) {
                val result =
                    withContext(Dispatchers.Main) {
                        TrackSelectionUtils.createTrackSelections(
                            onMain { player.trackSelectionParameters },
                            onMain { player.currentTracks },
                            currentPlayer.value!!.backend,
                            true,
                            audioIndex,
                            subtitleIndex,
                            source,
                        )
                    }
                if (result.bothSelected) {
                    onMain { player.trackSelectionParameters = result.trackSelectionParameters }
                    Timber.d("Changes tracks audio=$audioIndex, subtitle=$subtitleIndex")
                    val itemPlayback =
                        currentItemPlayback.copy(
                            sourceId = source.id?.toUUIDOrNull(),
                            audioIndex = audioIndex ?: TrackIndex.UNSPECIFIED,
                            subtitleIndex =
                                if (currentItemPlayback.subtitleIndex < 0) {
                                    currentItemPlayback.subtitleIndex
                                } else {
                                    subtitleIndex ?: TrackIndex.DISABLED
                                },
                        )
                    if (userInitiated) {
                        viewModelScope.launchIO {
                            Timber.v("Saving user initiated item playback: %s", itemPlayback)
                            val updated = itemPlaybackRepository.saveItemPlayback(itemPlayback)
                            withContext(Dispatchers.Main) {
                                this@PlaybackViewModel.currentItemPlayback.value = updated
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        this@PlaybackViewModel.currentPlayback.update {
                            (it ?: currentPlayback).copy(
                                tracks = checkForSupport(player.currentTracks),
                            )
                        }

                        this@PlaybackViewModel.currentItemPlayback.value = itemPlayback
                    }
                    loadSubtitleDelay()
                    return@withContext true
                }
            } else {
                Timber.v("changeStreams direct play, external subtitle was requested")
            }
            return@withContext false
        }

    fun changeAudioStream(index: Int) {
        viewModelScope.launchIO {
            // Skip if the same track is already active — prevents unnecessary restart
            if (currentItemPlayback.value?.audioIndex == index) {
                Timber.d("Audio track %s already active, skipping", index)
                return@launchIO
            }
            Timber.d("Changing audio track to %s", index)
            val itemPlayback =
                itemPlaybackRepository.saveTrackSelection(
                    item = item,
                    itemPlayback = currentItemPlayback.value!!,
                    trackIndex = index,
                    type = MediaStreamType.AUDIO,
                )
            this@PlaybackViewModel.currentItemPlayback.setValueOnMain(itemPlayback)

            val source = currentPlayback.value?.mediaSourceInfo
            val resolvedSubtitleIndex =
                if (source != null) {
                    streamChoiceService.resolveSubtitleIndex(
                        source = source,
                        audioStreamIndex = index,
                        seriesId = item.data.seriesId,
                        subtitleIndex = itemPlayback.subtitleIndex,
                        prefs = preferences,
                    )
                } else {
                    itemPlayback.subtitleIndex.takeIf { it >= 0 }
                }

            changeStreams(
                item,
                itemPlayback,
                index,
                resolvedSubtitleIndex,
                onMain { player.currentPosition },
                true,
            )
        }
    }

    fun changeSubtitleStream(index: Int): Job =
        viewModelScope.launchIO {
            // Skip if the same track is already active — prevents unnecessary restart
            if (currentItemPlayback.value?.subtitleIndex == index) {
                Timber.d("Subtitle track %s already active, skipping", index)
                return@launchIO
            }
            Timber.d("Changing subtitle track to %s", index)
            // Inform quality engine so it can apply subtitle risk penalty (spec §5)
            val subtitleCodec = currentMediaInfo.value?.subtitleStreams
                ?.firstOrNull { it.index == index }?.codec
            qualityManager.onSubtitleSelected(subtitleCodec)
            val itemPlayback =
                itemPlaybackRepository.saveTrackSelection(
                    item = item,
                    itemPlayback = currentItemPlayback.value!!,
                    trackIndex = index,
                    type = MediaStreamType.SUBTITLE,
                )
            this@PlaybackViewModel.currentItemPlayback.setValueOnMain(itemPlayback)

            val source = currentPlayback.value?.mediaSourceInfo
            val resolvedIndex =
                if (source != null) {
                    streamChoiceService.resolveSubtitleIndex(
                        source = source,
                        audioStreamIndex = itemPlayback.audioIndex,
                        seriesId = item.data.seriesId,
                        subtitleIndex = index,
                        prefs = preferences,
                    )
                } else {
                    index.takeIf { it >= 0 }
                }

            changeStreams(
                item,
                itemPlayback,
                itemPlayback.audioIndex,
                resolvedIndex,
                onMain { player.currentPosition },
                true,
            )
        }

    // ── Public function so the UI quality panel can change quality ─────────
    fun changeQuality(selection: QualitySelection) {
        viewModelScope.launchIO {
            // Same selection already active and not currently measuring → skip the refetch
            if (qualityManager.mode.value == selection && !qualityManager.isMeasuring.value) {
                Timber.d("Quality selection %s already active, skipping refetch", selection)
                return@launchIO
            }
            qualityManager.selectMode(selection)
            val pos = onMain { player.currentPosition }
            changeStreams(
                item = item,
                currentItemPlayback = currentItemPlayback.value!!,
                audioIndex = currentItemPlayback.value?.audioIndex,
                subtitleIndex = currentItemPlayback.value?.subtitleIndex,
                positionMs = pos,
                userInitiated = true,
                enableDirectPlay = qualityManager.effectiveEnableDirectPlay,
                enableDirectStream = true,
                allowAudioStreamCopy = qualityManager.effectiveAllowAudioStreamCopy,
                forceRebuild = true,
            )
        }
    }

    private suspend fun prefetchTrickplay(
        duration: Duration,
        trickplayInfo: TrickplayInfo,
        mediaSourceId: UUID?,
    ) {
        val tilesPerImage = trickplayInfo.tileWidth * trickplayInfo.tileHeight
        val totalCount =
            (duration.inWholeMilliseconds / trickplayInfo.interval).toInt() / tilesPerImage + 1
        (0..<totalCount).forEach {
            val url = getTrickplayUrl(it, trickplayInfo, mediaSourceId)
            context.imageLoader.enqueue(
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(Size.ORIGINAL)
                    .build(),
            )
        }
    }

    fun getTrickplayUrl(
        index: Int,
        trickPlayInfo: TrickplayInfo? = currentMediaInfo.value?.trickPlayInfo,
        mediaSourceId: UUID? = currentItemPlayback.value?.sourceId,
    ): String? =
        trickPlayInfo?.let {
            val itemId = item.id
            return api.trickplayApi.getTrickplayTileImageUrl(
                itemId,
                trickPlayInfo.width,
                index,
                mediaSourceId,
            )
        }

    // Watches for a single stall exceeding the rescue limit while buffering.
    private var stallJob: Job? = null

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                // Rebuffer while the user wants playback = potential starvation.
                // Guard windows (post-seek, post-rebuild, end-of-item, switching)
                // are applied inside the health tracker.
                if (player.playWhenReady) {
                    qualityManager.onRebuffer(
                        positionMs = player.currentPosition,
                        durationMs = player.duration.coerceAtLeast(0L),
                        isSwitchingStream = isSwitchingStream.value,
                    )
                    stallJob?.cancel()
                    stallJob = viewModelScope.launch {
                        delay(PlaybackHealthTracker.STALL_LIMIT_MS)
                        if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                            qualityManager.onStallExceeded(
                                positionMs = player.currentPosition,
                                durationMs = player.duration.coerceAtLeast(0L),
                                isSwitchingStream = isSwitchingStream.value,
                            )
                        }
                    }
                }
            }
            Player.STATE_READY -> {
                stallJob?.cancel()
                stallJob = null
            }
            Player.STATE_ENDED -> {
                stallJob?.cancel()
                stallJob = null
                viewModelScope.launchIO {
                    val nextItem = playlist.value?.peek()
                    Timber.v("Setting next up to ${nextItem?.id}")
                    withContext(Dispatchers.Main) {
                        nextUp.value = nextItem
                        if (nextItem == null) {
                            navigationManager.goBack()
                        }
                    }
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Post-seek rebuffering is normal — never counts toward a rescue.
        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        ) {
            qualityManager.onSeek()
        }
    }

    private var segmentJob: Job? = null
    private val autoSkippedSegments = mutableSetOf<UUID>()
    private val outroShownSegments = mutableSetOf<UUID>()

    private fun resetSegmentState() {
        segmentJob?.cancel()
        autoSkippedSegments.clear()
        outroShownSegments.clear()
        currentSegment.value = null
    }

    private fun listenForSegments(itemId: UUID) {
        segmentJob?.cancel()
        segmentJob =
            viewModelScope.launchIO {
                val prefs = preferences.appPreferences.playbackPreferences
                val segments by api.mediaSegmentsApi.getItemSegments(itemId)
                if (segments.items.isNotEmpty()) {
                    while (isActive) {
                        delay(500L)
                        val currentTicks =
                            onMain { player.currentPosition.milliseconds.inWholeTicks }
                        val currentSegment =
                            segments.items
                                .firstOrNull {
                                    it.type != MediaSegmentType.UNKNOWN && currentTicks >= it.startTicks && currentTicks < it.endTicks
                                }
                        if (currentSegment != null &&
                            currentSegment.itemId == this@PlaybackViewModel.itemId
                        ) {
                            if (currentSegment.id !=
                                this@PlaybackViewModel
                                    .currentSegment.value
                                    ?.segment
                                    ?.id
                            ) {
                                Timber.d(
                                    "Found media segment for %s: %s, %s",
                                    currentSegment.itemId,
                                    currentSegment.id,
                                    currentSegment.type,
                                )
                            }
                            val playlist = this@PlaybackViewModel.playlist.value

                            if (currentSegment.type == MediaSegmentType.OUTRO &&
                                prefs.showNextUpWhen == ShowNextUpWhen.DURING_CREDITS &&
                                playlist != null && playlist.hasNext() &&
                                outroShownSegments.add(currentSegment.id)
                            ) {
                                val nextItem = playlist.peek()
                                Timber.v("Setting next up during outro to ${nextItem?.id}")
                                withContext(Dispatchers.Main) {
                                    nextUp.value = nextItem
                                }
                            } else {
                                val behavior =
                                    when (currentSegment.type) {
                                        MediaSegmentType.COMMERCIAL -> prefs.skipCommercials
                                        MediaSegmentType.PREVIEW -> prefs.skipPreviews
                                        MediaSegmentType.RECAP -> prefs.skipRecaps
                                        MediaSegmentType.OUTRO -> prefs.skipOutros
                                        MediaSegmentType.INTRO -> prefs.skipIntros
                                        MediaSegmentType.UNKNOWN -> SkipSegmentBehavior.IGNORE
                                    }
                                withContext(Dispatchers.Main) {
                                    when (behavior) {
                                        SkipSegmentBehavior.AUTO_SKIP -> {
                                            if (autoSkippedSegments.add(currentSegment.id)) {
                                                onMain { player.seekTo(currentSegment.endTicks.ticks.inWholeMilliseconds + 1) }
                                            }
                                            this@PlaybackViewModel.currentSegment.update {
                                                MediaSegmentState(currentSegment, true)
                                            }
                                        }

                                        SkipSegmentBehavior.ASK_TO_SKIP -> {
                                            this@PlaybackViewModel.currentSegment.update {
                                                MediaSegmentState(
                                                    currentSegment,
                                                    autoSkippedSegments.contains(currentSegment.id),
                                                )
                                            }
                                        }

                                        else -> {
                                            this@PlaybackViewModel.currentSegment.value = null
                                        }
                                    }
                                }
                            }
                        } else if (currentSegment == null) {
                            withContext(Dispatchers.Main) {
                                this@PlaybackViewModel.currentSegment.value = null
                            }
                        }
                    }
                }
            }
    }

    fun updateSegment(
        segmentId: UUID?,
        dismissed: Boolean,
    ) {
        viewModelScope.launchDefault {
            val segment = currentSegment.value?.segment
            if (segment != null && segment.id == segmentId) {
                autoSkippedSegments.add(segment.id)
                if (dismissed) {
                    currentSegment.update {
                        it?.copy(interacted = true)
                    }
                } else {
                    currentSegment.update {
                        null
                    }
                    onMain { player.seekTo(segment.endTicks.ticks.inWholeMilliseconds + 1) }
                }
            }
        }
    }

    private fun listenForTranscodeReason(): Job =
        viewModelScope.launchIO {
            currentPlayback.collectLatest {
                if (it != null) {
                    try {
                        var transcodeInfo = it.transcodeInfo
                        while (isActive && it.playMethod == PlayMethod.TRANSCODE && transcodeInfo == null) {
                            delay(2.seconds)
                            transcodeInfo =
                                api.sessionApi
                                    .getSessions(deviceId = deviceInfo.id)
                                    .content
                                    .firstOrNull()
                                    ?.transcodingInfo
                            if (transcodeInfo == null) delay(3.seconds)
                        }
                        Timber.v("transcodeInfo=$transcodeInfo")
                        currentPlayback.update { current ->
                            current?.copy(transcodeInfo = transcodeInfo)
                        }
                    } catch (ex: Exception) {
                        if (ex !is CancellationException) {
                            Timber.w(ex, "Exception trying to get session info")
                            currentPlayback.update { current ->
                                current?.copy(transcodeInfo = null)
                            }
                        }
                    }
                }
            }
        }

    private var lastInteractionDate: Date = Date()

    fun reportInteraction() {
        lastInteractionDate = Date()
    }

    fun shouldAutoPlayNextUp(): Boolean =
        preferences.appPreferences.playbackPreferences.let {
            it.autoPlayNext &&
                    if (it.passOutProtectionMs > 0) {
                        (Date().time - lastInteractionDate.time) < it.passOutProtectionMs
                    } else {
                        true
                    }
        }

    fun playNextUp() {
        playlist.value?.let {
            if (it.hasNext()) {
                viewModelScope.launchIO {
                    cancelUpNextEpisode()
                    val item = it.getAndAdvance()
                    val played = play(item, 0)
                    if (!played) {
                        playNextUp()
                    }
                }
            }
        }
    }

    fun playPrevious() {
        playlist.value?.let {
            if (it.hasPrevious()) {
                viewModelScope.launchIO {
                    cancelUpNextEpisode()
                    val item = it.getPreviousAndReverse()
                    val played = play(item, 0)
                    if (!played) {
                        playPrevious()
                    }
                }
            }
        }
    }

    suspend fun cancelUpNextEpisode() {
        nextUp.setValueOnMain(null)
    }

    /**
     * Play an upcoming playlist item by id (Phase 2 Up-Next cards). Advances the
     * playlist index to the item and runs the same item-swap path as [playNextUp].
     */
    fun playPlaylistItem(id: UUID) {
        playlist.value?.let { playlist ->
            viewModelScope.launchIO {
                val toPlay = playlist.advanceTo(id)
                if (toPlay != null) {
                    val played = play(toPlay, 0)
                    if (!played) {
                        playNextUp()
                    }
                }
            }
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        currentPlayback.update {
            it?.copy(
                tracks = checkForSupport(tracks),
            )
        }
    }

    /**
     * ExoPlayer bandwidth estimate — a refinement signal for the measurement
     * store, NEVER a switch trigger. Gated to direct play/direct stream:
     * during transcodes Exo measures the server's transcode speed (Jellyfin
     * throttles ffmpeg once buffered ahead), not the network.
     */
    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        val method = currentPlayback.value?.playMethod
        if (method == PlayMethod.DIRECT_PLAY || method == PlayMethod.DIRECT_STREAM) {
            networkAnalyzer.recordExoSample(
                bitrateEstimate,
                serverRepository.currentUser.value?.id?.toString(),
            )
        }
    }

    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                _positionState.value = PlaybackPositionState(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0L),
                    bufferedMs = player.bufferedPosition,
                )
                delay(500L)
            }
        }
    }

    // Player.Listener version — fires for both ExoPlayer and MPV.
    // Delay the reset by 150 ms so Compose has at least 2-3 frames to observe
    // isSwitchingStream=true and paint the black cover before it's removed.
    // Without the delay, rapid streams (first frame decoded < 1 Compose frame
    // after setMediaItem) toggle true→false before Compose ever recomposes,
    // so the cover is never shown and the frozen-size guard never engages.
    override fun onRenderedFirstFrame() {
        // Buffer must prime after any (re)build before starvation counts again.
        qualityManager.onPlaybackStarted()
        Timber.i(
            "[STREAM-SWITCH] First frame rendered: method=%s player=#%d",
            currentPlayback.value?.playMethod?.serialName ?: "unknown",
            System.identityHashCode(player),
        )
        startPositionPolling()
        maybeShowContentWarning()
        viewModelScope.launch(Dispatchers.Main) {
            delay(150L)
            isSwitchingStream.update { false }
            Timber.i("[STREAM-SWITCH] isSwitchingStream cleared after first frame delay")
        }
    }

    /**
     * Fetches the engine's content advisories for the current title and, if any, briefly shows the passive
     * overlay — once per subject (a movie, or a series for episodic content), so stream switches and
     * per-episode transitions within a series don't re-trigger it. Fails soft (no overlay) when the engine
     * is unavailable or has nothing to warn about.
     */
    private fun maybeShowContentWarning() {
        val item = currentPlayback.value?.item ?: return
        val subjectId = warningSubjectId(item)
        if (subjectId == warningShownForSubjectId) return
        warningShownForSubjectId = subjectId

        val episodic = item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON
        viewModelScope.launchIO {
            val response =
                engineClient.getContentWarnings(
                    jellyfinId = subjectId,
                    tmdbId = if (episodic) null else item.data.providerIds?.get("Tmdb")?.toIntOrNull(),
                )
            if (response != null && response.hasWarnings && response.warnings.isNotEmpty()) {
                contentWarning.value = response
                delay(8_000L)
                if (contentWarning.value === response) {
                    contentWarning.value = null
                }
            }
        }
    }

    /** The id warnings are keyed by: the series id for episodic content, else the item's own id. */
    private fun warningSubjectId(item: BaseItem): UUID =
        if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) {
            item.data.seriesId ?: item.id
        } else {
            item.id
        }

    override fun onCues(cueGroup: CueGroup) {
        subtitleCues.value = cueGroup.cues
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val displayW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
        val displayH = videoSize.height
        val method = currentPlayback.value?.playMethod?.serialName ?: "unknown"
        Timber.i(
            "[PLAYBACK-DIAG] VideoSize: coded=%dx%d SAR=%.3f display=%dx%d method=%s",
            videoSize.width, videoSize.height,
            videoSize.pixelWidthHeightRatio,
            displayW, displayH,
            method,
        )
        if (videoSize.width > 0 && videoSize.pixelWidthHeightRatio != 1f) {
            // Non-square SAR means display width ≠ coded width.  ContentScale.Fit uses the
            // display dimensions; a low SAR (< 1) shrinks display width and can cause a
            // sub-screen PlayerSurface.  The Crop override in PlaybackPage handles this for
            // transcoded streams; log so the mismatch is visible in diagnostics.
            Timber.w(
                "[PLAYBACK-DIAG] Non-square SAR=%.3f: display width %dpx differs from coded %dpx",
                videoSize.pixelWidthHeightRatio,
                displayW, videoSize.width,
            )
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Playback error")
        isSwitchingStream.update { false }
        viewModelScope.launch(Dispatchers.Main + ExceptionHandler()) {
            currentPlayback.value?.let {
                when (it.playMethod) {
                    PlayMethod.TRANSCODE -> {
                        loading.setValueOnMain(
                            LoadingState.Error(
                                "Error during playback",
                                error,
                            ),
                        )
                    }

                    PlayMethod.DIRECT_STREAM, PlayMethod.DIRECT_PLAY -> {
                        Timber.w("Playback error during ${it.playMethod}, falling back to transcoding")
                        changeStreams(
                            item,
                            currentItemPlayback.value!!,
                            currentItemPlayback.value?.audioIndex,
                            currentItemPlayback.value?.subtitleIndex,
                            player.currentPosition,
                            false,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            forceRebuild = true,
                        )
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
        }
    }

    fun release() {
        Timber.v("release")
        disconnectPlayer()
        activityListener = null
    }

    fun subscribe(): Job =
        api.webSocket
            .subscribe<PlaystateMessage>()
            .onEach { message ->
                message.data?.let {
                    withContext(Dispatchers.Main) {
                        when (it.command) {
                            PlaystateCommand.STOP -> {
                                release()
                                navigationManager.goBack()
                            }

                            PlaystateCommand.PAUSE -> {
                                player.pause()
                            }

                            PlaystateCommand.UNPAUSE -> {
                                player.play()
                            }

                            PlaystateCommand.NEXT_TRACK -> {
                                playNextUp()
                            }

                            PlaystateCommand.PREVIOUS_TRACK -> {
                                playPrevious()
                            }

                            PlaystateCommand.SEEK -> {
                                it.seekPositionTicks?.ticks?.let {
                                    player.seekTo(
                                        it.inWholeMilliseconds,
                                    )
                                }
                            }

                            PlaystateCommand.REWIND -> {
                                player.seekBack(
                                    preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                                )
                            }

                            PlaystateCommand.FAST_FORWARD -> {
                                player.seekForward(
                                    preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                                )
                            }

                            PlaystateCommand.PLAY_PAUSE -> {
                                if (player.isPlaying) player.pause() else player.play()
                            }
                        }
                    }
                }
            }.launchIn(viewModelScope)

    internal suspend fun updateCurrentMedia(block: (CurrentMediaInfo) -> CurrentMediaInfo) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val newMediaInfo = block.invoke(currentMediaInfo.value!!)
                currentMediaInfo.setValueOnMain(newMediaInfo)
            }
        }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Timber.v("onVideoDecoderInitialized: decoder=$decoderName")
        currentPlayback.update { it?.copy(videoDecoder = decoderName) }
    }

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        Timber.d("onVideoDisabled")
        currentPlayback.update { it?.copy(videoDecoder = null) }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        decoderReuseEvaluation?.let { decoder ->
            if (decoder.result != DecoderReuseEvaluation.REUSE_RESULT_NO) {
                Timber.d("onVideoInputFormatChanged: decoder=${decoder.decoderName}")
                currentPlayback.update { it?.copy(videoDecoder = decoder.decoderName) }
            }
        }
        // Log format metadata for all playback modes to surface dimension/FPS anomalies.
        val fps = format.frameRate
        val method = currentPlayback.value?.playMethod?.serialName ?: "unknown"
        val container = currentPlayback.value?.mediaSourceInfo?.container ?: "unknown"
        Timber.i(
            "[PLAYBACK-DIAG] VideoFormat: %dx%d fps=%.2f codec=%s container=%s method=%s",
            format.width, format.height, fps, format.sampleMimeType, container, method,
        )
        if (fps > 120f) {
            // 192fps is a known ExoPlayer symptom when the MPEG-TS muxer outputs timestamps
            // that map to ~5ms per frame in the 90kHz PTS domain. The actual playback frame
            // rate is correct; this only affects the metadata-reported value.
            Timber.w(
                "[PLAYBACK-DIAG] INVALID FPS DETECTED: %.1f fps (method=%s container=%s) " +
                    "— MPEG-TS PTS timestamp corruption. A/V sync may be affected.",
                fps, method, container,
            )
        }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Timber.d("decoder: onAudioDecoderInitialized: decoder=$decoderName")
        currentPlayback.update { it?.copy(audioDecoder = decoderName) }
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        decoderReuseEvaluation?.let { decoder ->
            if (decoder.result != DecoderReuseEvaluation.REUSE_RESULT_NO) {
                Timber.d("decoder: onAudioInputFormatChanged: decoder=${decoder.decoderName}")
                currentPlayback.update { it?.copy(audioDecoder = decoder.decoderName) }
            }
        }
    }

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        Timber.d("decoder: onAudioDisabled")
        currentPlayback.update { it?.copy(audioDecoder = null) }
    }

    private var subtitleDelaySaveJob: Job? = null

    fun updateSubtitleDelay(delta: Duration) {
        subtitleDelaySaveJob?.cancel()
        currentPlayback.update {
            it?.let {
                val newDelay = it.subtitleDelay + delta
                val result = it.copy(subtitleDelay = it.subtitleDelay + delta)
                subtitleDelaySaveJob =
                    viewModelScope.launchIO {
                        currentItemPlayback.value?.let { item ->
                            delay(1500)
                            itemPlaybackRepository.saveTrackModifications(
                                item.itemId,
                                item.subtitleIndex,
                                newDelay,
                            )
                        }
                    }
                result
            }
        }
    }

    suspend fun loadSubtitleDelay() {
        currentItemPlayback.value?.let {
            if (it.subtitleIndexEnabled) {
                val result =
                    itemPlaybackRepository.getTrackModifications(it.itemId, it.subtitleIndex)
                if (result != null) {
                    Timber.v(
                        "Loading subtitle delay %s for track=%s, itemId=%s",
                        result.delayMs,
                        it.subtitleIndex,
                        it.itemId,
                    )
                    currentPlayback.update { it?.copy(subtitleDelay = result.delayMs.milliseconds) }
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        screensaverService.keepScreenOn(isPlaying)
    }
}

data class PlayerState(
    val player: Player,
    val backend: PlayerBackend,
    val assHandler: AssHandler?,
)

data class MediaSegmentState(
    val segment: MediaSegmentDto,
    val interacted: Boolean,
)

data class PlaybackPositionState(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
)