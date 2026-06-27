package com.github.jkrishna289.orcax.ui.main

import com.github.jkrishna289.orcax.engine.AvailabilityState
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

/**
 * A fully client-side [RenderBundle] that mirrors the source "Orca Home" prototype catalog
 * (Project Loki spotlight + For You / Recently Added / Continue Watching / Trending rows). The
 * engine home renders this verbatim when a real Orca Engine server can't serve a bundle, so the
 * cinematic layout is visible out of the box. Items carry no Jellyfin id — they're decorative — so
 * cards paint from [accentColorHint] (see EngineHomeArt) and clicks/playback are inert.
 *
 * The instant a real server returns a bundle, that wins; this is the unavailable-path stand-in only.
 */
object SampleEngineBundle {
    /** Title + one-line tag + accent hint, matching the prototype's catalog entries. */
    private data class Entry(
        val title: String,
        val tag: String,
        val accent: String,
        val type: MediaType = MediaType.SERIES,
    )

    private val catalog =
        mapOf(
            "loki" to Entry("Project Loki", "Crime · Mystery", "#E8B53A"),
            "wondertools" to Entry("The Wondertools", "Comedy · Family", "#FFCF6B"),
            "dhindora" to Entry("Dhindora", "Comedy", "#9FD8FF"),
            "gumball" to Entry("Gumball", "Animation · Comedy", "#FF5A8A"),
            "mouse" to Entry("Mouse", "Thriller · Crime", "#4AA3DF"),
            "euphoria" to Entry("Euphoria", "Drama", "#FF3D8B"),
            "ifwishes" to Entry("If Wishes Could Kill", "Horror · Thriller", "#FF3B30", MediaType.MOVIE),
            "nocturne" to Entry("Nocturne", "Mystery", "#36C6D9"),
            "ember" to Entry("Ember & Ash", "Fantasy · Adventure", "#FFA64A"),
            "verdict" to Entry("The Verdict", "Crime · Drama", "#DAB440"),
            "silentbay" to Entry("Silent Bay", "Thriller", "#5FD0C4"),
            "papermoon" to Entry("Paper Moon", "Romance · Drama", "#F0C0C8", MediaType.MOVIE),
        )

    private fun entry(key: String): Entry = catalog.getValue(key)

    private fun mediaId(
        e: Entry,
        availability: AvailabilityState = AvailabilityState.WATCH_NOW,
    ) = MediaId(
        source = MediaSource.JELLYFIN,
        jellyfinId = null,
        tmdbId = null,
        mediaType = e.type,
        availability = availability,
    )

    private fun poster(
        key: String,
        badges: List<CardBadge> = emptyList(),
        type: CardType = CardType.POSTER_PORTRAIT,
        availability: AvailabilityState = AvailabilityState.WATCH_NOW,
        actions: List<CardAction> = listOf(CardAction.PLAY, CardAction.DETAILS),
    ): RenderItem {
        val e = entry(key)
        return RenderItem(
            media = mediaId(e, availability),
            card =
                CardDescriptor(
                    type = type,
                    imageType = CardImageType.PRIMARY,
                    aspectRatio = CardAspectRatio.TALL,
                    title = e.title,
                    subtitle = e.tag,
                    showTitle = true,
                    badges = badges,
                    actions = actions,
                    accentColorHint = e.accent,
                ),
        )
    }

    private fun wide(
        key: String,
        progress: Double,
        subtitle: String,
    ): RenderItem {
        val e = entry(key)
        return RenderItem(
            media = mediaId(e),
            card =
                CardDescriptor(
                    type = CardType.BANNER_WIDE,
                    imageType = CardImageType.THUMB,
                    aspectRatio = CardAspectRatio.WIDE,
                    title = e.title,
                    subtitle = subtitle,
                    showTitle = true,
                    showProgress = true,
                    progress = progress,
                    actions = listOf(CardAction.RESUME, CardAction.PLAY),
                    accentColorHint = e.accent,
                ),
        )
    }

    private fun hero(
        key: String,
        tagline: String,
        cert: String,
        ageBadge: String,
        rating: String,
        year: String,
        episodes: String,
        genres: List<String>,
    ): RenderItem {
        val e = entry(key)
        val badges =
            buildList {
                add(CardBadge(kind = "CERT", text = cert))
                add(CardBadge(kind = "AGE", text = ageBadge))
                add(CardBadge(kind = "RATING", text = rating))
                add(CardBadge(kind = "YEAR", text = year))
                add(CardBadge(kind = "EPISODES", text = episodes))
                genres.forEach { add(CardBadge(kind = "GENRE", text = it)) }
            }
        return RenderItem(
            media = mediaId(e),
            card =
                CardDescriptor(
                    type = CardType.HERO,
                    imageType = CardImageType.BACKDROP,
                    aspectRatio = CardAspectRatio.WIDE,
                    title = e.title,
                    subtitle = tagline,
                    showTitle = true,
                    wantsTrailer = false,
                    badges = badges,
                    actions = listOf(CardAction.PLAY, CardAction.DETAILS),
                    accentColorHint = e.accent,
                ),
        )
    }

    val bundle: RenderBundle by lazy {
        RenderBundle(
            contractVersion = 1,
            rows =
                listOf(
                    RenderRow(
                        id = SPOTLIGHT_ROW_ID,
                        title = "Spotlight",
                        rowStyle = RowStyle.HERO,
                        items =
                            listOf(
                                hero(
                                    key = "loki",
                                    tagline =
                                        "Join Loki and Lorelei as they crack puzzles, solve cases, and " +
                                            "uncover dark secrets within the halls of Clark University.",
                                    cert = "TV-MA",
                                    ageBadge = "16+",
                                    rating = "10.0",
                                    year = "2026",
                                    episodes = "9 Episodes",
                                    genres = listOf("Crime", "Mystery", "Thriller"),
                                ),
                                hero(
                                    key = "euphoria",
                                    tagline =
                                        "A group of high-school students navigate love and friendships in a " +
                                            "world of drugs, sex, trauma and social media.",
                                    cert = "TV-MA",
                                    ageBadge = "18+",
                                    rating = "8.4",
                                    year = "2025",
                                    episodes = "16 Episodes",
                                    genres = listOf("Drama"),
                                ),
                                hero(
                                    key = "ember",
                                    tagline =
                                        "Two rival heirs must forge an uneasy alliance when an ancient fire " +
                                            "threatens to consume the last free kingdom.",
                                    cert = "TV-14",
                                    ageBadge = "13+",
                                    rating = "9.1",
                                    year = "2026",
                                    episodes = "8 Episodes",
                                    genres = listOf("Fantasy", "Adventure"),
                                ),
                            ),
                    ),
                    RenderRow(
                        id = "for_you",
                        title = "For You",
                        rowStyle = RowStyle.STANDARD,
                        items =
                            listOf("loki", "wondertools", "dhindora", "gumball", "mouse", "euphoria", "ifwishes", "nocturne")
                                .map { poster(it) },
                    ),
                    RenderRow(
                        id = "recently",
                        title = "Recently Added",
                        rowStyle = RowStyle.STANDARD,
                        items =
                            listOf("ifwishes", "ember", "verdict", "silentbay", "papermoon", "mouse", "nocturne", "loki")
                                .map {
                                    poster(
                                        it,
                                        badges = listOf(CardBadge(kind = "NEW", text = "NEW")),
                                        availability = AvailabilityState.RECENTLY_ADDED,
                                    )
                                },
                    ),
                    RenderRow(
                        id = "continue",
                        title = "Continue Watching",
                        rowStyle = RowStyle.STANDARD,
                        items =
                            listOf(
                                wide("mouse", progress = 0.62, subtitle = "S1 · E5 · 24 min left"),
                                wide("euphoria", progress = 0.28, subtitle = "S2 · E3 · 41 min left"),
                                wide("verdict", progress = 0.84, subtitle = "S1 · E8 · 9 min left"),
                                wide("loki", progress = 0.14, subtitle = "S1 · E1 · 47 min left"),
                                wide("silentbay", progress = 0.48, subtitle = "S1 · E4 · 31 min left"),
                            ),
                    ),
                    RenderRow(
                        id = "trending",
                        title = "Trending Now",
                        rowStyle = RowStyle.TOP10,
                        items =
                            listOf("verdict", "mouse", "euphoria", "loki", "ifwishes", "nocturne", "ember", "dhindora")
                                .mapIndexed { index, key ->
                                    poster(
                                        key,
                                        badges = listOf(CardBadge(kind = "RANK", text = (index + 1).toString())),
                                        type = CardType.TOP_RANKED,
                                    )
                                },
                    ),
                    RenderRow(
                        id = "discover",
                        title = "Request to Watch",
                        rowStyle = RowStyle.STANDARD,
                        items =
                            listOf("papermoon", "silentbay", "wondertools", "gumball", "dhindora", "nocturne")
                                .map {
                                    poster(
                                        it,
                                        type = CardType.DISCOVER,
                                        availability = AvailabilityState.REQUEST,
                                        actions = listOf(CardAction.REQUEST),
                                    )
                                },
                    ),
                ),
        )
    }

    /** Must match [EngineHomeViewModel]'s spotlight row id. */
    private const val SPOTLIGHT_ROW_ID = "spotlight"
}
