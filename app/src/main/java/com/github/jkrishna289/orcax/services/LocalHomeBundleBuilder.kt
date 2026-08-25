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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a real [RenderBundle] from the signed-in user's Jellyfin library, so the engine-driven home
 * (billboard + rows) shows actual content + artwork within about a second of launch. It runs
 * **alongside** the server-side Orca Engine request rather than after it: the engine's personalized
 * bundle wins whenever it arrives, but the viewer is never left watching a spinner while it builds —
 * and if the engine is unreachable (offline, not installed, or an old build) this simply stands as
 * the home. It reuses the very same data path as the legacy home
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
        /**
         * Emits the home **as it materialises** rather than after the slowest row: first a bundle of
         * empty placeholder rows in the user's configured order (the page renders an empty row as a
         * shimmer), then the whole bundle again each time a row's items land. Content is therefore on
         * screen in about a second instead of after every row has resolved.
         *
         * Rows keep their configured slot, so a late one fills in place rather than shoving its
         * neighbours around; a row that fails or comes back empty drops out. Emits nothing at all
         * when there's no signed-in user or no configured rows, letting the caller fall back.
         */
        fun progressive(): Flow<RenderBundle> =
            channelFlow {
                val userDto = serverRepository.currentUserDto.value ?: return@channelFlow
                val prefs =
                    runCatching { userPreferencesService.getCurrent().appPreferences.homePagePreferences }
                        .getOrNull() ?: return@channelFlow
                val libraries =
                    runCatching { navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess) }
                        .getOrDefault(emptyList())

                // The legacy home loads these at login; ensure they're present on the engine path too.
                if (homeSettingsService.currentSettings.value == HomePageResolvedSettings.EMPTY) {
                    runCatching { homeSettingsService.loadCurrentSettings(userDto.id) }
                }
                val settings = homeSettingsService.currentSettings.value
                if (settings.rows.isEmpty()) return@channelFlow

                // One slot per configured row, held in configured order. Slots start empty (= "still
                // loading" to the page) and are replaced or dropped as each fetch resolves.
                val slots = LinkedHashMap<Int, RenderRow>()
                settings.rows.forEach { display ->
                    slots[display.id] = RenderRow(id = display.rowId(), title = display.title, rowStyle = RowStyle.STANDARD)
                }
                // Fetched state per row id, so the spotlight is re-derived in CONFIGURED order and
                // doesn't depend on which row happened to finish first.
                val fetchedById = mutableMapOf<Int, HomeRowLoadingState?>()

                suspend fun emit() {
                    val heroItems = buildHeroItems(settings.rows.map { it to fetchedById[it.id] })
                    send(
                        RenderBundle(
                            contractVersion = CARD_CONTRACT_VERSION,
                            rows =
                                buildList {
                                    if (heroItems.isNotEmpty()) {
                                        add(RenderRow(id = SPOTLIGHT_ROW_ID, title = "Spotlight", rowStyle = RowStyle.HERO, items = heroItems))
                                    }
                                    addAll(slots.values)
                                },
                        ),
                    )
                }

                // Paint the layout (billboard + shimmer rows) before a single fetch returns.
                emit()

                // Fetch every configured row in parallel (bounded), tolerating per-row failures.
                // The mutex serialises slot updates so each emission is a consistent snapshot.
                val semaphore = Semaphore(4)
                val slotLock = Mutex()
                settings.rows
                    .map { display ->
                        launch(Dispatchers.IO) {
                            val state =
                                semaphore.withPermit {
                                    runCatching {
                                        homeSettingsService.fetchDataForRow(
                                            row = display.config,
                                            scope = this@channelFlow,
                                            prefs = prefs,
                                            userDto = userDto,
                                            libraries = libraries,
                                            limit = prefs.maxItemsPerRow,
                                            isRefresh = false,
                                        )
                                    }.onFailure { Timber.w(it, "Local home: row '%s' failed", display.title) }
                                        .getOrNull()
                                }

                            slotLock.withLock {
                                fetchedById[display.id] = state
                                val row = display.toRenderRow(state)
                                if (row == null) slots.remove(display.id) else slots[display.id] = row
                                emit()
                            }
                        }
                    }.joinAll()
            }

        /**
         * The complete local bundle, for callers that can't render progressively (the engine card
         * preview). Null when nothing resolved — same contract as before.
         */
        suspend fun build(): RenderBundle? = progressive().lastOrNull()?.takeIf { it.rows.isNotEmpty() }

        /** A configured row's stable render id — must be stable across emissions to keep D-pad focus. */
        private fun HomeRowConfigDisplay.rowId(): String = "row_$id"

        /** The rendered row for a completed fetch, or null when it failed / came back empty. */
        private fun HomeRowConfigDisplay.toRenderRow(state: HomeRowLoadingState?): RenderRow? {
            val items = (state as? HomeRowLoadingState.Success)?.items?.filterNotNull().orEmpty()
            if (items.isEmpty()) return null
            val resume = config.isWatching()
            val recent = config.isRecentlyAdded()
            return RenderRow(
                id = rowId(),
                title = (state as HomeRowLoadingState.Success).title.ifBlank { title },
                rowStyle = RowStyle.STANDARD,
                items = items.map { it.toRenderItem(resume = resume, recentlyAdded = recent) },
            )
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
