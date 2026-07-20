package com.github.jkrishna289.orcax.services

import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.HomeRowConfig
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CARD_CONTRACT_VERSION
import com.github.jkrishna289.orcax.engine.CardAction
import com.github.jkrishna289.orcax.engine.CardAspectRatio
import com.github.jkrishna289.orcax.engine.CardBadge
import com.github.jkrishna289.orcax.engine.CardDescriptor
import com.github.jkrishna289.orcax.engine.CardImageType
import com.github.jkrishna289.orcax.engine.CardType
import com.github.jkrishna289.orcax.engine.MediaId
import com.github.jkrishna289.orcax.engine.MediaSource
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderBundle
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.engine.RowStyle
import com.github.jkrishna289.orcax.util.HomeRowLoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a real [RenderBundle] from the signed-in user's Jellyfin library as a **fallback** for when
 * the server-side Wholphin Engine plugin is unreachable (offline, not installed, or an old build), so
 * the engine-driven home (billboard + rows) still shows actual content + artwork. When the server
 * engine responds it wins; this only fills the gap. It reuses the very same data path as the legacy home
 * ([HomeSettingsService.fetchDataForRow]) so the rows mirror the user's configured home and every
 * item carries its Jellyfin id — which is all the cards/billboard need to resolve posters, backdrops
 * and logos via [ImageUrlService].
 *
 * Returns null when there's no signed-in user or no resolvable content, letting the caller fall back.
 */
@Singleton
class LocalHomeBundleBuilder
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val navDrawerService: NavDrawerService,
        private val homeSettingsService: HomeSettingsService,
        private val userPreferencesService: UserPreferencesService,
    ) {
        suspend fun build(): RenderBundle? =
            coroutineScope {
                val userDto = serverRepository.currentUserDto.value ?: return@coroutineScope null
                val prefs =
                    runCatching { userPreferencesService.getCurrent().appPreferences.homePagePreferences }
                        .getOrNull() ?: return@coroutineScope null
                val libraries =
                    runCatching { navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess) }
                        .getOrDefault(emptyList())

                // The legacy home loads these at login; ensure they're present on the engine path too.
                if (homeSettingsService.currentSettings.value == HomePageResolvedSettings.EMPTY) {
                    runCatching { homeSettingsService.loadCurrentSettings(userDto.id) }
                }
                val settings = homeSettingsService.currentSettings.value
                if (settings.rows.isEmpty()) return@coroutineScope null

                // Fetch every configured row in parallel (bounded), tolerating per-row failures.
                val semaphore = Semaphore(4)
                val fetched =
                    settings.rows
                        .map { display ->
                            async(Dispatchers.IO) {
                                display to
                                    semaphore.withPermit {
                                        runCatching {
                                            homeSettingsService.fetchDataForRow(
                                                row = display.config,
                                                scope = this@coroutineScope,
                                                prefs = prefs,
                                                userDto = userDto,
                                                libraries = libraries,
                                                limit = prefs.maxItemsPerRow,
                                                isRefresh = false,
                                            )
                                        }.onFailure { Timber.w(it, "Local home: row '%s' failed", display.title) }
                                            .getOrNull()
                                    }
                            }
                        }.awaitAll()

                val contentRows =
                    fetched.mapNotNull { (display, state) ->
                        val success = state as? HomeRowLoadingState.Success ?: return@mapNotNull null
                        val items = success.items.filterNotNull()
                        if (items.isEmpty()) return@mapNotNull null
                        val resume = display.config.isWatching()
                        val recent = display.config.isRecentlyAdded()
                        RenderRow(
                            id = "row_${display.id}",
                            title = success.title.ifBlank { display.title },
                            rowStyle = RowStyle.STANDARD,
                            items = items.map { it.toRenderItem(resume = resume, recentlyAdded = recent) },
                        )
                    }
                if (contentRows.isEmpty()) return@coroutineScope null

                val heroItems = buildHeroItems(fetched)
                val rows =
                    buildList {
                        if (heroItems.isNotEmpty()) {
                            add(RenderRow(id = SPOTLIGHT_ROW_ID, title = "Spotlight", rowStyle = RowStyle.HERO, items = heroItems))
                        }
                        addAll(contentRows)
                    }
                RenderBundle(contractVersion = CARD_CONTRACT_VERSION, rows = rows)
            }

        /** Picks a few movies/series across the fetched rows to feature in the rotating spotlight. */
        private fun buildHeroItems(fetched: List<Pair<HomeRowConfigDisplay, HomeRowLoadingState?>>): List<RenderItem> =
            fetched
                // Lead with recently-added/released rows — they make the freshest spotlight.
                .sortedByDescending { (display, _) -> if (display.config.isRecentlyAdded()) 1 else 0 }
                .flatMap { (_, state) -> (state as? HomeRowLoadingState.Success)?.items?.filterNotNull().orEmpty() }
                .filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES }
                .distinctBy { it.id }
                .take(SPOTLIGHT_COUNT)
                .map { it.toHeroItem() }

        /** A standard row card: portrait poster, or a wide progress banner for resume/episode items. */
        private fun BaseItem.toRenderItem(
            resume: Boolean,
            recentlyAdded: Boolean,
        ): RenderItem {
            val episode = type == BaseItemKind.EPISODE
            val percent = data.userData?.playedPercentage
            val showProgress = resume && percent != null
            val wide = showProgress || episode
            return RenderItem(
                media =
                    MediaId(
                        source = MediaSource.JELLYFIN,
                        jellyfinId = id.toString(),
                        tmdbId = tmdbId(),
                        mediaType = type.toEngineMediaType(),
                        availability = AvailabilityState.WATCH_NOW,
                    ),
                card =
                    CardDescriptor(
                        type = if (wide) CardType.BANNER_WIDE else CardType.POSTER_PORTRAIT,
                        imageType = if (wide) CardImageType.THUMB else CardImageType.PRIMARY,
                        aspectRatio = if (wide) CardAspectRatio.WIDE else CardAspectRatio.TALL,
                        title = title ?: name,
                        subtitle = subtitle,
                        showTitle = true,
                        showProgress = showProgress,
                        progress = percent?.let { (it / 100.0).coerceIn(0.0, 1.0) },
                        // Engine unavailable → no cached provider logo; surface the Jellyfin studio name as text.
                        badges =
                            buildList {
                                if (recentlyAdded) add(CardBadge(kind = "NEW", text = "NEW"))
                                // Resume cards show a "N min left" chip computed from the item's runtime.
                                if (showProgress) timeLeftBadge(percent)?.let { add(it) }
                                data.studios?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                                    ?.let { add(CardBadge(kind = "STUDIO", text = it)) }
                            },
                        actions =
                            buildList {
                                if (resume) add(CardAction.RESUME)
                                add(CardAction.PLAY)
                                add(CardAction.DETAILS)
                            },
                    ),
            )
        }

        /** A spotlight hero: wide backdrop + the metadata badges the billboard reads (cert/rating/year/…). */
        private fun BaseItem.toHeroItem(): RenderItem =
            RenderItem(
                media =
                    MediaId(
                        source = MediaSource.JELLYFIN,
                        jellyfinId = id.toString(),
                        tmdbId = tmdbId(),
                        mediaType = type.toEngineMediaType(),
                        availability = AvailabilityState.WATCH_NOW,
                    ),
                card =
                    CardDescriptor(
                        type = CardType.HERO,
                        imageType = CardImageType.BACKDROP,
                        aspectRatio = CardAspectRatio.WIDE,
                        title = name,
                        subtitle = data.taglines?.firstOrNull()?.takeIf { it.isNotBlank() } ?: data.overview,
                        showTitle = true,
                        wantsTrailer = false,
                        badges =
                            buildList {
                                data.officialRating?.let { add(CardBadge(kind = "CERT", text = it)) }
                                data.communityRating?.let {
                                    add(CardBadge(kind = "RATING", text = String.format(Locale.US, "%.1f", it)))
                                }
                                data.productionYear?.let { add(CardBadge(kind = "YEAR", text = it.toString())) }
                                if (type == BaseItemKind.SERIES) {
                                    data.childCount?.takeIf { it > 0 }?.let { add(CardBadge(kind = "EPISODES", text = "$it Episodes")) }
                                }
                                data.genres?.take(3)?.forEach { add(CardBadge(kind = "GENRE", text = it)) }
                            },
                        actions = listOf(CardAction.PLAY, CardAction.DETAILS),
                    ),
            )

        /**
         * A "N min left" [CardBadge] for a resume card, from the item's runtime and how far the user
         * has watched. Null when there's no runtime or under a minute remains (nothing useful to show).
         */
        private fun BaseItem.timeLeftBadge(percent: Double?): CardBadge? {
            val totalTicks = data.runTimeTicks?.takeIf { it > 0 } ?: return null
            val watchedFraction = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
            val remainingTicks = (totalTicks * (1.0 - watchedFraction)).toLong()
            val minutes = remainingTicks / TICKS_PER_MINUTE
            if (minutes < 1) return null
            return CardBadge(kind = "TIMELEFT", text = "$minutes min left")
        }

        private fun BaseItem.tmdbId(): Int? = data.providerIds?.get("Tmdb")?.toIntOrNull()

        private fun HomeRowConfig.isWatching(): Boolean =
            this is HomeRowConfig.ContinueWatching ||
                this is HomeRowConfig.NextUp ||
                this is HomeRowConfig.ContinueWatchingCombined

        private fun HomeRowConfig.isRecentlyAdded(): Boolean =
            this is HomeRowConfig.RecentlyAdded || this is HomeRowConfig.RecentlyReleased

        private fun BaseItemKind.toEngineMediaType(): MediaType =
            when (this) {
                BaseItemKind.MOVIE -> MediaType.MOVIE
                BaseItemKind.SERIES -> MediaType.SERIES
                BaseItemKind.SEASON -> MediaType.SEASON
                BaseItemKind.EPISODE -> MediaType.EPISODE
                BaseItemKind.PERSON -> MediaType.PERSON
                BaseItemKind.BOX_SET -> MediaType.COLLECTION
                else -> MediaType.OTHER
            }

        companion object {
            /** Must match [EngineHomeViewModel]'s spotlight row id. */
            private const val SPOTLIGHT_ROW_ID = "spotlight"

            /** How many items the rotating spotlight cycles through. */
            private const val SPOTLIGHT_COUNT = 5

            /** Jellyfin runtime ticks per minute (100-ns ticks × 60 s). */
            private const val TICKS_PER_MINUTE = 600_000_000L
        }
    }
