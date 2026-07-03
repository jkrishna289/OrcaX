package com.github.jkrishna289.orcax.ui.playback

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.Dimension
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.PlayMethod
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.view.children
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.surfaceColorAtElevation
import com.github.jkrishna289.orcax.data.model.ItemPlayback
import com.github.jkrishna289.orcax.data.model.Playlist
import com.github.jkrishna289.orcax.preferences.AssPlaybackMode
import com.github.jkrishna289.orcax.preferences.PlayerBackend
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.preferences.skipBackOnResume
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.components.ErrorMessage
import com.github.jkrishna289.orcax.ui.components.LoadingPage
import com.github.jkrishna289.orcax.ui.components.rememberLogoUrl
import com.github.jkrishna289.orcax.ui.ifElse
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.preferences.subtitle.SubtitleSettings.applyToMpv
import com.github.jkrishna289.orcax.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.jkrishna289.orcax.ui.preferences.subtitle.SubtitleSettings.toSubtitleStyle
import com.github.jkrishna289.orcax.ui.seasonEpisode
import com.github.jkrishna289.orcax.ui.seekBack
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.LoadingState
import com.github.jkrishna289.orcax.util.Media3SubtitleOverride
import com.github.jkrishna289.orcax.util.mpv.MpvPlayer
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import com.github.jkrishna289.orcax.ui.getTimeFormatter
import java.time.LocalTime
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The actual playback page which shows media & playback controls
 */
@OptIn(UnstableApi::class)
@Composable
fun PlaybackPage(
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel =
        hiltViewModel<PlaybackViewModel, PlaybackViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    LifecycleStartEffect(destination) {
        onStopOrDispose {
            viewModel.release()
        }
    }

    val loading by viewModel.loading.observeAsState(LoadingState.Loading)
    when (val st = loading) {
        is LoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        LoadingState.Pending,
        LoadingState.Loading,
        -> {
            LoadingPage(modifier.background(Color.Black))
        }

        LoadingState.Success -> {
            val playerState by viewModel.currentPlayer.collectAsState()
            PlaybackPageContent(
                playerState = playerState!!,
                preferences = preferences,
                destination = destination,
                viewModel = viewModel,
                modifier = modifier,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlaybackPageContent(
    playerState: PlayerState,
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel,
) {
    val player = playerState.player
    val playerBackend = playerState.backend

    val prefs = preferences.appPreferences.playbackPreferences
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val mediaInfo by viewModel.currentMediaInfo.observeAsState()
    val userDto by viewModel.currentUserDto.observeAsState()

    val currentPlayback by viewModel.currentPlayback.collectAsState()
    val currentItemPlayback by viewModel.currentItemPlayback.observeAsState(
        ItemPlayback(
            userId = -1,
            itemId = UUID.randomUUID(),
        ),
    )
    val currentSegment by viewModel.currentSegment.collectAsState()
    val contentWarning by viewModel.contentWarning.collectAsState()

    val cues by viewModel.subtitleCues.observeAsState(listOf())
    var showDebugInfo by remember { mutableStateOf(prefs.showDebugInfo) }

    val nextUp by viewModel.nextUp.observeAsState(null)
    val playlist by viewModel.playlist.observeAsState(Playlist(listOf()))

    val subtitleSearch by viewModel.subtitleSearchStatus.observeAsState(null)
    val subtitleSearchLanguage by viewModel.subtitleSearchLanguage.observeAsState(Locale.current.language)

    var playbackDialog by remember { mutableStateOf<PlaybackDialogType?>(null) }
    LaunchedEffect(player) {
        if (playerBackend == PlayerBackend.MPV) {
            scope.launch(Dispatchers.IO + ExceptionHandler()) {
                // MPV can't play HDR, so always use regular settings
                preferences.appPreferences.interfacePreferences.subtitlesPreferences.applyToMpv(
                    configuration,
                    density,
                )
            }
        }
    }

    var contentScale by remember(playerBackend) {
        mutableStateOf(
            if (playerBackend == PlayerBackend.MPV) {
                ContentScale.FillBounds
            } else {
                prefs.globalContentScale.scale
            },
        )
    }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

    val subtitleDelay = currentPlayback?.subtitleDelay ?: Duration.ZERO
    LaunchedEffect(subtitleDelay) {
        (player as? MpvPlayer)?.subtitleDelay = subtitleDelay
    }

    val presentationState = rememberPresentationState(player, false)
    // Transcoded MPEG-TS streams frequently carry incorrect SAR (pixel aspect ratio) in their
    // container headers. ContentScale.Fit trusts the reported display dimensions, so a bad SAR
    // (e.g. pixelWidthHeightRatio << 1.0) produces a surface much smaller than the screen.
    // Override to Crop (RESIZE_MODE_ZOOM) for transcoded streams — it always fills the screen
    // while preserving aspect ratio, regardless of what the TS metadata reports.
    val effectiveContentScale =
        if (contentScale == ContentScale.Fit && currentPlayback?.playMethod == PlayMethod.TRANSCODE) {
            ContentScale.Crop
        } else {
            contentScale
        }
    val scaledModifier =
        Modifier.resizeWithContentScale(effectiveContentScale, presentationState.videoSizeDp)
    val focusRequester = remember { FocusRequester() }
    val playPauseState = rememberPlayPauseButtonState(player)
    val seekBarState = rememberSeekBarState(player, scope)

    LaunchedEffect(Unit) {
        focusRequester.tryRequestFocus()
    }
    val controllerViewState = remember { viewModel.controllerViewState }

    // ── Phase state (unified 3-phase overlay) ───────────────────────────────
    val phaseViewModel: PlaybackPhaseViewModel = viewModel()
    val phaseState by phaseViewModel.uiState.collectAsStateWithLifecycle()

    // Single focus owner: while any overlay (Phase 1 toolbar or Phase 2 paused
    // overlay) is showing, the phase system owns focus via its own requesters.
    // Only pull focus back to the video surface when nothing is overlaid.
    val overlayShowing = phaseState.phase == PlaybackPhase.PAUSED_OVERLAY ||
        phaseState.toolbarVisibility == ToolbarVisibility.VISIBLE
    LaunchedEffect(overlayShowing) {
        if (!overlayShowing) focusRequester.tryRequestFocus()
    }

    // ── Stream-switch cover ─────────────────────────────────────────────────
    val isSwitchingStream by viewModel.isSwitchingStream.collectAsState()

    // ── Stream-switch diagnostics — log key layout state during transitions ─
    LaunchedEffect(effectiveContentScale) {
        Timber.i("[STREAM-SWITCH] UI contentScale=%s method=%s",
            effectiveContentScale, currentPlayback?.playMethod?.serialName ?: "none")
    }
    LaunchedEffect(presentationState.videoSizeDp) {
        val sz = presentationState.videoSizeDp
        Timber.i("[STREAM-SWITCH] UI videoSizeDp=%s method=%s isSwitching=%b",
            sz, currentPlayback?.playMethod?.serialName ?: "none", isSwitchingStream)
    }
    LaunchedEffect(isSwitchingStream) {
        Timber.i("[STREAM-SWITCH] UI isSwitchingStream=%b method=%s videoSizeDp=%s",
            isSwitchingStream, currentPlayback?.playMethod?.serialName ?: "none",
            presentationState.videoSizeDp)
    }

    // ── Live position — single polling source in ViewModel ─────────────────
    val positionState by viewModel.positionState.collectAsState()
    val currentPositionMs = positionState.positionMs
    val currentDurationMs = positionState.durationMs
    val currentBufferedMs = positionState.bufferedMs
    val endsAtString = remember(currentPositionMs, currentDurationMs) {
        if (currentDurationMs > 0L) {
            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
            val remainingMs = ((currentDurationMs - currentPositionMs).coerceAtLeast(0L) / speed).toLong()
            val remainingMin = remainingMs / 60_000L
            val endTime = LocalTime.now().plusSeconds(remainingMs / 1000L)
            "Ends at ${getTimeFormatter().format(endTime)} • ${remainingMin}m left"
        } else ""
    }

    // ── Quality label for the capsule ───────────────────────────────────────
    val resolvedQualityTier by viewModel.qualityManager.resolvedTier.collectAsState()
    val selectedQualityTier by viewModel.qualityManager.selectedTier.collectAsState()
    val isMeasuring by viewModel.qualityManager.isMeasuring.collectAsState()
    val qualityLabel = resolvedQualityTier?.label ?: selectedQualityTier.label

    // ── Sync progress + playing state to PlaybackPhaseViewModel ────────────
    LaunchedEffect(currentPositionMs, currentDurationMs, currentBufferedMs) {
        phaseViewModel.updateProgress(currentPositionMs, currentDurationMs, currentBufferedMs)
    }
    // Drive phase transitions from ExoPlayer's actual playing state.
    // showPlay=true means the player is paused (showing a ▶ button); false means playing.
    LaunchedEffect(playPauseState.showPlay) {
        phaseViewModel.onVideoPlayingChanged(!playPauseState.showPlay)
    }
    // Jellyfin logo (stylized wordmark) for the current item; falls back to the
    // series logo for episodes. Null when the item has no logo image.
    val logoUrl = rememberLogoUrl(currentPlayback?.item)
    LaunchedEffect(currentPlayback, logoUrl) {
        val item = currentPlayback?.item
        phaseViewModel.updateMetadata(
            showTitle   = item?.title ?: item?.name ?: "",
            episodeName = item?.name ?: "",
            season      = item?.data?.parentIndexNumber ?: 1,
            episode     = item?.data?.indexNumber ?: 1,
            synopsis    = item?.data?.overview ?: "",
            year        = item?.data?.productionYear?.toString() ?: "",
            rating      = item?.data?.officialRating ?: "",
            logoUrl     = logoUrl ?: ""
        )
    }

    // ── Phase 2 UP NEXT cards — current + deeper upcoming episodes from the playlist ──
    // The old version only ever produced 2 cards (current + single nextUp), so the
    // deepened/expandable rail never had anything to reveal. Pull the remaining queue
    // from the playlist (up to 4 ahead) so the rail can actually expand.
    val imageUrlService = LocalImageUrlService.current
    LaunchedEffect(currentPlayback, playlist, playlist.index, nextUp) {
        val items = buildList {
            currentPlayback?.item?.let { current ->
                add(
                    UpNextItem(
                        episodeNumber       = current.data.indexNumber ?: 0,
                        title               = current.name ?: "",
                        durationLabel       = formatUpNextDurationMs(currentDurationMs),
                        thumbnailUrl        = imageUrlService.getItemImageUrl(current, ImageType.PRIMARY) ?: "",
                        isCurrentlyWatching = true
                    )
                )
            }
            // Prefer the full playlist queue; fall back to the single nextUp item.
            val upcoming = runCatching { if (playlist.hasNext()) playlist.upcomingItems() else emptyList() }
                .getOrDefault(emptyList())
            val deeper = upcoming.ifEmpty { listOfNotNull(nextUp) }
            deeper.take(4).forEach { next ->
                val durationMs = (next.data.runTimeTicks ?: 0L) / 10_000L
                add(
                    UpNextItem(
                        episodeNumber       = next.data.indexNumber ?: 0,
                        title               = next.name ?: "",
                        durationLabel       = formatUpNextDurationMs(durationMs),
                        thumbnailUrl        = imageUrlService.getItemImageUrl(next, ImageType.PRIMARY) ?: "",
                        isCurrentlyWatching = false
                    )
                )
            }
        }
        phaseViewModel.updateUpNext(items)
    }

    // ── Phase 2 COMPACT ambient info (cast + all metadata) ──────────────────
    // Built straight from the full-detail playback item (userLibraryApi.getItem
    // returns people/genres/studios/taglines/ratings); URLs via the image service.
    LaunchedEffect(currentPlayback) {
        val item = currentPlayback?.item
        if (item == null) {
            phaseViewModel.updateAmbient(AmbientInfo())
            return@LaunchedEffect
        }
        val people = item.data.people.orEmpty()
        val cast = people
            .filter {
                it.type == PersonKind.ACTOR ||
                    it.type == PersonKind.GUEST_STAR ||
                    it.type == PersonKind.UNKNOWN
            }
            .mapNotNull { p ->
                p.name?.takeIf { it.isNotBlank() }?.let { name ->
                    AmbientPerson(
                        name     = name,
                        role     = p.role ?: "",
                        imageUrl = imageUrlService.getItemImageUrl(p.id, ImageType.PRIMARY)
                    )
                }
            }
        phaseViewModel.updateAmbient(
            AmbientInfo(
                backdropUrl     = imageUrlService.getItemImageUrl(item, ImageType.BACKDROP) ?: "",
                genres          = item.data.genres.orEmpty(),
                studios         = item.data.studios.orEmpty().mapNotNull { it.name },
                cast            = cast,
                directors       = people.filter { it.type == PersonKind.DIRECTOR }.mapNotNull { it.name },
                writers         = people.filter { it.type == PersonKind.WRITER }.mapNotNull { it.name },
                communityRating = item.data.communityRating,
                tagline         = item.data.taglines?.firstOrNull() ?: ""
            )
        )
    }

    LaunchedEffect(mediaInfo, currentItemPlayback) {
        val audioStreams = mediaInfo?.audioStreams.orEmpty()
        val subtitleStreams = mediaInfo?.subtitleStreams.orEmpty()
        val audioItems = audioStreams.map { RouletteItem(title = it.displayTitle, meta = it.codec ?: "") }
        val subtitleItems = subtitleStreams.map { RouletteItem(title = it.displayTitle, meta = it.language ?: "") }
        val audioIdx = audioStreams.indexOfFirst { it.index == currentItemPlayback?.audioIndex }.coerceAtLeast(0)
        val subtitleIdx = subtitleStreams.indexOfFirst { it.index == currentItemPlayback?.subtitleIndex }.takeIf { it >= 0 } ?: -1
        phaseViewModel.updateTracks(audioItems, subtitleItems, audioIdx, subtitleIdx.coerceAtLeast(0))
    }

    // ── Bitstream toast: fire phaseViewModel.onPlaybackStarted when tracks
    //    are confirmed; reset the once-per-content flag on item transitions.
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audioFormat = tracks.groups
                    .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
                    ?.getTrackFormat(0)
                val videoFormat = tracks.groups
                    .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.getTrackFormat(0)
                val audioLabel = bitstreamAudioLabel(audioFormat)
                val videoLabel = bitstreamVideoLabel(videoFormat)
                // Only show toast when at least one premium format is detected
                if (audioLabel != null || videoLabel != null) {
                    phaseViewModel.onPlaybackStarted(
                        audioLabel ?: "",   // empty string hides that row in BitstreamToast
                        videoLabel ?: "",
                    )
                }
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                phaseViewModel.resetBitstreamFlag()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var skipIndicatorDuration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controllerViewState.controlsVisible) {
        // If controller shows/hides, immediately cancel the skip indicator
        skipIndicatorDuration = 0L
    }
    var skipPosition by remember { mutableLongStateOf(0L) }
    val updateSkipIndicator = { delta: Long ->
        if ((skipIndicatorDuration > 0 && delta < 0) || (skipIndicatorDuration < 0 && delta > 0)) {
            skipIndicatorDuration = 0
        }
        skipIndicatorDuration += delta
        skipPosition = player.currentPosition
    }
    val keyHandler =
        PlaybackKeyHandler(
            player = player,
            controlsEnabled = nextUp == null,
            skipWithLeftRight = true,
            seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
            seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
            getDurationMs = { player.duration.coerceAtLeast(0L) },
            controllerViewState = controllerViewState,
            updateSkipIndicator = updateSkipIndicator,
            skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
            onInteraction = {
                viewModel.reportInteraction()
                phaseViewModel.onUserInteraction()
            },
            oneClickPause = preferences.appPreferences.playbackPreferences.oneClickPause,
            onStop = {
                player.stop()
                viewModel.navigationManager.goBack()
            },
            onPlaybackDialogTypeClick = { playbackDialog = it },
            isOverlayActive = {
                phaseState.toolbarVisibility == ToolbarVisibility.VISIBLE ||
                    phaseState.phase == PlaybackPhase.PAUSED_OVERLAY
            },
        )

    val onPlaybackActionClick: (PlaybackAction) -> Unit = {
        when (it) {
            is PlaybackAction.PlaybackSpeed -> {
                playbackSpeed = it.value
            }

            is PlaybackAction.Scale -> {
                contentScale = it.scale
            }

            PlaybackAction.ShowDebug -> {
                showDebugInfo = !showDebugInfo
            }

            PlaybackAction.ShowPlaylist -> {
                TODO()
            }

            PlaybackAction.ShowVideoFilterDialog -> {
                TODO()
            }

            is PlaybackAction.ToggleAudio -> {
                viewModel.changeAudioStream(it.index)
            }

            is PlaybackAction.ToggleCaptions -> {
                viewModel.changeSubtitleStream(it.index)
            }

            PlaybackAction.SearchCaptions -> {
                controllerViewState.hideControls()
                viewModel.searchForSubtitles()
            }

            PlaybackAction.Next -> {
                viewModel.playNextUp()
                playbackDialog = null
                focusRequester.tryRequestFocus()
            }

            PlaybackAction.Previous -> {
                val pos = player.currentPosition
                if (pos < player.maxSeekToPreviousPosition && playlist.hasPrevious()) {
                    viewModel.playPrevious()
                } else {
                    player.seekToPrevious()
                }
                playbackDialog = null
                focusRequester.tryRequestFocus()
            }

            is PlaybackAction.ChangeQuality -> {
                viewModel.changeQuality(it.tier)
            }
        }
    }

    val showSegment =
        currentSegment?.interacted == false &&
            nextUp == null && !controllerViewState.controlsVisible && skipIndicatorDuration == 0L
    BackHandler(showSegment) {
        viewModel.updateSegment(currentSegment?.segment?.id, true)
    }
    // Phase 1 toolbar Audio/Sub dropdown open → close it (don't exit the player)
    BackHandler(phaseState.dropdown != null) { phaseViewModel.closeDropdown() }
    // Phase 2 roulette picker open → close just the picker, not the whole overlay
    BackHandler(phaseState.p2PickerType != null) { phaseViewModel.closeP2Picker() }
    // Phase 2 FULL (incl. idle-dimmed) → peel to the COMPACT scrubber first
    BackHandler(
        phaseState.phase == PlaybackPhase.PAUSED_OVERLAY &&
            phaseState.p2PickerType == null &&
            phaseState.phase2SubState == Phase2SubState.FULL,
    ) { phaseViewModel.compactPhase2() }
    // Phase 2 COMPACT → dismiss the overlay to the bare paused frame
    BackHandler(
        phaseState.phase == PlaybackPhase.PAUSED_OVERLAY &&
            phaseState.p2PickerType == null &&
            phaseState.phase2SubState == Phase2SubState.COMPACT,
    ) { phaseViewModel.dismissPhase2() }
    // Phase 1 toolbar visible (nothing open) → hide the toolbar
    BackHandler(
        phaseState.phase == PlaybackPhase.ACTIVE &&
            phaseState.toolbarVisibility == ToolbarVisibility.VISIBLE &&
            phaseState.dropdown == null,
    ) { phaseViewModel.hideToolbar() }
    // Bare ACTIVE frame (no chrome showing) → exit the player. Together with the
    // handlers above this yields "first BACK dismisses chrome, next BACK exits".
    BackHandler(
        phaseState.phase == PlaybackPhase.ACTIVE &&
            phaseState.toolbarVisibility == ToolbarVisibility.HIDDEN &&
            phaseState.dropdown == null &&
            phaseState.p2PickerType == null &&
            !showSegment &&
            nextUp == null,
    ) { viewModel.navigationManager.goBack() }

    Box(
        modifier
            .background(if (nextUp == null) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        val playerSize by animateFloatAsState(if (nextUp == null) 1f else .6f)
        Box(
            modifier =
                Modifier
                    .fillMaxSize(playerSize)
                    .align(Alignment.TopCenter)
                    .onKeyEvent(keyHandler::onKeyEvent)
                    .focusRequester(focusRequester)
                    .focusable(),
        ) {
            var playerSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier =
                    scaledModifier.onSizeChanged {
                        playerSurfaceSize = it
                    },
            )
            // Cover the surface while it is not yet ready (Media3 signal) OR while
            // a stream switch is in progress.  The stream-switch cover prevents the
            // "small box in the top-left" glitch that occurs when a transcoded stream
            // reports different video dimensions than the previous stream: the old
            // SurfaceView frame becomes visible at the wrong size before the player
            // redraws at the new dimensions.  Reset happens in onRenderedFirstFrame().
            if (presentationState.coverSurface || isSwitchingStream) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black),
                ) {
                    LoadingPage(focusEnabled = false)
                }
            }

            // If D-pad skipping, show the amount skipped in an animation
            if (!controllerViewState.controlsVisible && skipIndicatorDuration != 0L) {
                SkipIndicator(
                    durationMs = skipIndicatorDuration,
                    onFinish = {
                        skipIndicatorDuration = 0L
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 70.dp),
                )
                // Show a small progress bar along the bottom of the screen
                val showSkipProgress = true // TODO get from preferences
                if (showSkipProgress) {
                    val percent = skipPosition.toFloat() / player.duration.toFloat()
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .background(MaterialTheme.colorScheme.border)
                                .clip(RectangleShape)
                                .height(3.dp)
                                .fillMaxWidth(percent),
                    )
                }
            }

            if (!controllerViewState.controlsVisible && skipIndicatorDuration == 0L) {
                PauseIndicator(
                    player = player,
                    modifier =
                        Modifier
                            .align(Alignment.Center),
                )
            }

            // Passive content advisory (engine/Groq): fades in briefly at playback start, never takes focus.
            ContentWarningOverlay(
                warnings = contentWarning,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .zIndex(2f),
            )

            val subtitleSettings =
                remember(mediaInfo) {
                    Timber.v("subtitle choice: ${mediaInfo?.videoStream?.hdr}")
                    if (mediaInfo?.videoStream?.hdr == true) {
                        preferences.appPreferences.interfacePreferences.hdrSubtitlesPreferences
                    } else {
                        preferences.appPreferences.interfacePreferences.subtitlesPreferences
                    }
                }
            val subtitleImageOpacity =
                remember(subtitleSettings) { subtitleSettings.imageSubtitleOpacity / 100f }

            // Subtitles — rendered above the Phase 1 toolbar (zIndex), and only while
            // ACTIVE so they don't paint over the Phase 2 cinematic overlay.
            if (skipIndicatorDuration == 0L && currentItemPlayback.subtitleIndexEnabled &&
                !presentationState.coverSurface && phaseState.phase == PlaybackPhase.ACTIVE) {
                val maxSize by animateFloatAsState(if (controllerViewState.controlsVisible) .7f else 1f)
                val isImageSubtitles = remember(cues) { cues.firstOrNull()?.bitmap != null }
                AndroidView(
                    factory = { context ->
                        SubtitleView(context).apply {
                            subtitleSettings.let {
                                setStyle(it.toSubtitleStyle())
                                setFixedTextSize(Dimension.SP, it.fontSize.toFloat())
                                setBottomPaddingFraction(it.margin.toFloat() / 100f)
                            }
                            playerState.assHandler?.let { assHandler ->
                                if (prefs.overrides.assPlaybackMode == AssPlaybackMode.ASS_LIBASS) {
                                    Timber.v("Adding AssSubtitleView")
                                    addView(
                                        AssSubtitleView(context, assHandler).apply {
                                            layoutParams =
                                                FrameLayout
                                                    .LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ).apply { gravity = Gravity.CENTER }
                                        },
                                    )
                                }
                            }
                        }
                    },
                    update = {
                        it.setCues(cues)
                        Media3SubtitleOverride(subtitleSettings.calculateEdgeSize(density))
                            .apply(it)
                        it.children.firstOrNull { it is AssSubtitleView }?.let {
                            (it as? AssSubtitleView)?.apply {
                                val resized =
                                    layoutParams.let { it.width != playerSurfaceSize.width || it.height != playerSurfaceSize.height }
                                if (resized && playerSurfaceSize.width > 0 && playerSurfaceSize.height > 0) {
                                    Timber.v("Resizing AssSubtitleView: $playerSurfaceSize")
                                    layoutParams =
                                        FrameLayout
                                            .LayoutParams(
                                                playerSurfaceSize.width,
                                                playerSurfaceSize.height,
                                            ).apply { gravity = Gravity.CENTER }
                                }
                            }
                        }
                    },
                    onReset = {
                        it.setCues(null)
                    },
                    modifier =
                        Modifier
                            .zIndex(1f)
                            .fillMaxSize(maxSize)
                            .align(Alignment.TopCenter)
                            .background(Color.Transparent)
                            .ifElse(isImageSubtitles, Modifier.alpha(subtitleImageOpacity)),
                )
            }

            // ── Unified 3-phase playback overlay ─────────────────────────────
            PlaybackScreen(
                viewModel = phaseViewModel,
                onPlayPauseRequested = {
                    // Always use player.isPlaying — it's the source of truth, not ViewModel state
                    if (player.isPlaying) player.pause() else player.play()
                },
                onSeekRequested = { positionMs ->
                    player.seekTo(positionMs)
                },
                onCCRequested = { playbackDialog = PlaybackDialogType.CAPTIONS },
                onAudioDialogRequested = { playbackDialog = PlaybackDialogType.AUDIO },
                onConfirmRoulette = { type, index ->
                    when (type) {
                        RouletteType.AUDIO -> {
                            val stream = mediaInfo?.audioStreams?.getOrNull(index)
                            if (stream != null) viewModel.changeAudioStream(stream.index)
                        }
                        RouletteType.SUBTITLE -> {
                            val stream = mediaInfo?.subtitleStreams?.getOrNull(index)
                            if (stream != null) viewModel.changeSubtitleStream(stream.index)
                        }
                    }
                }
            )
        }

        // Ask to skip intros, etc button
        AnimatedVisibility(
            showSegment,
            modifier =
                Modifier
                    .padding(40.dp)
                    .align(Alignment.BottomEnd),
        ) {
            currentSegment?.let { segment ->
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    focusRequester.tryRequestFocus()
                    delay(10.seconds)
                    viewModel.updateSegment(segment.segment.id, true)
                }
                SkipSegmentButton(
                    type = segment.segment.type,
                    onClick = {
                        viewModel.updateSegment(segment.segment.id, false)
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }

        // Next up episode
        BackHandler(nextUp != null) {
            if (player.isPlaying) {
                scope.launch(ExceptionHandler()) {
                    viewModel.cancelUpNextEpisode()
                }
            } else {
                viewModel.navigationManager.goBack()
            }
        }
        AnimatedVisibility(
            nextUp != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter),
        ) {
            nextUp?.let {
                var autoPlayEnabled by remember { mutableStateOf(viewModel.shouldAutoPlayNextUp()) }
                var timeLeft by remember {
                    mutableLongStateOf(
                        preferences.appPreferences.playbackPreferences.autoPlayNextDelaySeconds,
                    )
                }
                BackHandler(timeLeft > 0 && autoPlayEnabled) {
                    timeLeft = -1
                    autoPlayEnabled = false
                }
                if (autoPlayEnabled) {
                    LaunchedEffect(Unit) {
                        if (timeLeft == 0L) {
                            viewModel.playNextUp()
                        } else {
                            while (timeLeft > 0) {
                                delay(1.seconds)
                                timeLeft--
                            }
                            if (timeLeft == 0L && autoPlayEnabled) {
                                viewModel.playNextUp()
                            }
                        }
                    }
                }
                NextUpEpisode(
                    title =
                        listOfNotNull(
                            it.data.seasonEpisode,
                            it.name,
                        ).joinToString(" - "),
                    description = it.data.overview,
                    imageUrl = LocalImageUrlService.current.rememberImageUrl(it),
                    aspectRatio = it.aspectRatio ?: AspectRatios.WIDE,
                    onClick = {
                        viewModel.reportInteraction()
                        controllerViewState.hideControls()
                        viewModel.playNextUp()
                    },
                    timeLeft = if (autoPlayEnabled) timeLeft.seconds else null,
                    modifier =
                        Modifier
                            .padding(8.dp)
//                                    .height(128.dp)
                            .fillMaxHeight(1 - playerSize)
                            .fillMaxWidth(.66f)
                            .align(Alignment.BottomCenter)
                            .background(
                                MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                                shape = RoundedCornerShape(8.dp),
                            ),
                )
            }
        }
    }

    subtitleSearch?.let { state ->
        val wasPlaying = remember { player.isPlaying }
        LaunchedEffect(Unit) {
            player.pause()
        }
        val onDismissRequest = {
            if (wasPlaying) {
                player.play()
            }
            viewModel.cancelSubtitleSearch()
        }
        Dialog(
            onDismissRequest = onDismissRequest,
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
        ) {
            DownloadSubtitlesContent(
                state = state,
                language = subtitleSearchLanguage,
                onSearch = { lang ->
                    viewModel.searchForSubtitles(lang)
                },
                onClickDownload = {
                    viewModel.downloadAndSwitchSubtitles(it.id, wasPlaying)
                },
                onDismissRequest = onDismissRequest,
                modifier =
                    Modifier
                        .widthIn(max = 640.dp)
                        .heightIn(max = 400.dp),
            )
        }
    }

    playbackDialog?.let { type ->
        PlaybackDialog(
            type = type,
            settings =
                PlaybackSettings(
                    showDebugInfo = showDebugInfo,
                    audioIndex = currentItemPlayback?.audioIndex,
                    audioStreams = mediaInfo?.audioStreams.orEmpty(),
                    subtitleIndex = currentItemPlayback?.subtitleIndex,
                    subtitleStreams = mediaInfo?.subtitleStreams.orEmpty(),
                    playbackSpeed = playbackSpeed,
                    contentScale = contentScale,
                    subtitleDelay = subtitleDelay,
                    hasSubtitleDownloadPermission =
                        remember(userDto) { userDto?.policy?.let { it.isAdministrator || it.enableSubtitleManagement } == true },
                    // TODO Passing through audio prevents changing playback speed
                    // See https://github.com/damontecres/Wholphin/issues/164
                    playbackSpeedEnabled = playerBackend == PlayerBackend.MPV || currentPlayback?.audioDecoder != null,
                ),
            onDismissRequest = {
                playbackDialog = null
                if (controllerViewState.controlsVisible) {
                    controllerViewState.pulseControls()
                }
            },
            onControllerInteraction = {
                controllerViewState.pulseControls(Long.MAX_VALUE)
            },
            onClickPlaybackDialogType = {
                if (it == PlaybackDialogType.SUBTITLE_DELAY) {
                    // Hide controls so subtitles are fully visible
                    controllerViewState.hideControls()
                }
                playbackDialog = it
            },
            onPlaybackActionClick = onPlaybackActionClick,
            onChangeSubtitleDelay = { viewModel.updateSubtitleDelay(it) },
            enableSubtitleDelay = player is MpvPlayer,
            enableVideoScale = player !is MpvPlayer,
        )
    }

    // Quality selection panel — intercepted here because PlaybackDialog renders Unit for QUALITY
    if (playbackDialog == PlaybackDialogType.QUALITY) {
        QualitySelectionPanel(
            selectedTier = selectedQualityTier,
            resolvedTier = resolvedQualityTier,
            isMeasuring = isMeasuring,
            videoStream = mediaInfo?.videoStream,
            onSelectTier = { onPlaybackActionClick(PlaybackAction.ChangeQuality(it)) },
            onDismiss = { playbackDialog = null },
        )
    }
}

private fun formatUpNextDurationMs(ms: Long): String {
    if (ms <= 0L) return ""
    val totalMin = ms / 60_000L
    val h = totalMin / 60L
    val m = totalMin % 60L
    return if (h > 0L) "${h}h %02dm".format(m) else "${m}m"
}

private fun bitstreamAudioLabel(format: androidx.media3.common.Format?): String? {
    if (format == null) return null
    val mime = format.sampleMimeType?.lowercase() ?: ""
    return when {
        mime.contains("truehd")                            -> "Dolby TrueHD"
        mime.contains("eac3") && format.channelCount >= 8 -> "Dolby Atmos"
        mime.contains("eac3")                              -> "Dolby Digital+"
        mime.contains("ac3")                               -> "Dolby Digital"
        mime.contains("dts-hd") || mime.contains("dtshd") -> "DTS-HD"
        mime.contains("dts")                               -> "DTS"
        else -> null   // non-premium: AAC, MP3, Opus, Vorbis etc.
    }
}

private fun bitstreamVideoLabel(format: androidx.media3.common.Format?): String? {
    if (format == null) return null
    val transfer   = format.colorInfo?.colorTransfer ?: 0
    val colorSpace = format.colorInfo?.colorSpace    ?: 0
    return when {
        transfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "Dolby Vision"
        transfer == androidx.media3.common.C.COLOR_TRANSFER_HLG    -> "HDR10"
        colorSpace == androidx.media3.common.C.COLOR_SPACE_BT2020  -> "HDR10"
        else -> null   // SDR, Full HD, H.264 etc.
    }
}
