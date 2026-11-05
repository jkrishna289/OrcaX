package com.github.jkrishna289.orcax.services

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.jkrishna289.orcax.preferences.ThemeSongVolume
import com.github.jkrishna289.orcax.services.hilt.AuthOkHttpClient
import com.github.jkrishna289.orcax.util.profile.Codec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a title's theme music while its page is open. A theme from the library comes first; a show
 * without one, or one that isn't in the library at all, gets the theme the Orca Engine fetched for it.
 * Themes fade in and out rather than cutting, and coming back to a show whose theme is still playing
 * leaves it playing instead of starting it over.
 */
@OptIn(UnstableApi::class)
@Singleton
class ThemeSongPlayer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:AuthOkHttpClient private val authOkHttpClient: OkHttpClient,
        private val api: ApiClient,
        private val engine: OrcaEngineClient,
    ) {
        private val player: Player by lazy {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        OkHttpDataSource.Factory(authOkHttpClient),
                    ),
                ).build()
        }

        private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var fade: Job? = null

        /** Whose theme is loaded: a library item's id, or "tmdb:<id>" for a show that isn't in the library. */
        private var playingFor: String? = null

        /**
         * Plays the theme for [itemId] (a library item), else the engine's for [tmdbId] (a series, in the
         * library or not). Returns whether there was anything to play.
         */
        suspend fun playThemeFor(
            itemId: UUID?,
            volume: ThemeSongVolume,
            tmdbId: Int? = null,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val level = volume.level() ?: return@withContext false
                val key = itemId?.toString() ?: tmdbId?.let { "tmdb:$it" } ?: return@withContext false
                if (withContext(Dispatchers.Main) { key == playingFor && player.isPlaying }) return@withContext true

                val fromLibrary =
                    itemId?.let { id ->
                        runCatching { api.libraryApi.getThemeSongs(id).content.items.randomOrNull() }
                            .onFailure { Timber.w(it, "Theme songs for %s", id) }
                            .getOrNull()
                    }
                val url =
                    fromLibrary?.let { theme ->
                        api.universalAudioApi.getUniversalAudioStreamUrl(
                            theme.id,
                            container = listOf(Codec.Audio.OPUS, Codec.Audio.MP3, Codec.Audio.AAC, Codec.Audio.FLAC),
                        )
                    }
                        // A 404 just means the engine hasn't fetched it yet (and now has it queued): silence
                        ?: tmdbId?.let { engine.themeUrl(it) }
                        ?: return@withContext false
                Timber.v("Theme for %s from %s", key, if (fromLibrary != null) "the library" else "the engine")
                withContext(Dispatchers.Main) { start(key, url, level) }
                true
            }

        /** Fades the theme out. Safe from any thread. */
        fun stop() {
            main.launch {
                playingFor = null
                if (player.isPlaying) {
                    Timber.v("Stopping theme song")
                    fadeTo(0f, FADE_OUT_MS) { player.stop() }
                }
            }
        }

        private fun start(
            key: String,
            url: String,
            level: Float,
        ) {
            fade?.cancel()
            playingFor = key
            player.apply {
                stop()
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
            fadeTo(level, FADE_IN_MS)
        }

        /** A timed ramp on the main thread; a newer fade replaces one still running. */
        private fun fadeTo(
            target: Float,
            durationMs: Long,
            then: (() -> Unit)? = null,
        ) {
            fade?.cancel()
            fade =
                main.launch {
                    val from = player.volume
                    val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1)
                    for (i in 1..steps) {
                        player.volume = from + (target - from) * i / steps
                        delay(FADE_STEP_MS)
                    }
                    then?.invoke()
                }
        }

        private fun ThemeSongVolume.level(): Float? =
            when (this) {
                ThemeSongVolume.UNRECOGNIZED,
                ThemeSongVolume.DISABLED,
                -> null

                ThemeSongVolume.LOWEST -> .05f
                ThemeSongVolume.LOW -> .1f
                ThemeSongVolume.MEDIUM -> .25f
                ThemeSongVolume.HIGH -> .5f
                // Was 75f, which the player clamps to full volume
                ThemeSongVolume.HIGHEST -> .75f
            }

        private companion object {
            const val FADE_IN_MS = 1_500L
            const val FADE_OUT_MS = 800L
            const val FADE_STEP_MS = 30L
        }
    }
