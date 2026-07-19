package com.github.jkrishna289.orcax.ui.main

import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CardAction
import com.github.jkrishna289.orcax.engine.CardAspectRatio
import com.github.jkrishna289.orcax.engine.CardBadge
import com.github.jkrishna289.orcax.engine.CardDescriptor
import com.github.jkrishna289.orcax.engine.CardImageType
import com.github.jkrishna289.orcax.engine.CardSize
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
        size: CardSize = CardSize.STANDARD,
        subtitle: String? = null,
    ): RenderItem {
        val e = entry(key)
        return RenderItem(
            media = mediaId(e, availability),
            card =
                CardDescriptor(
                    type = type,
                    imageType = CardImageType.PRIMARY,
                    aspectRatio = CardAspectRatio.TALL,
                    size = size,
                    title = e.title,
                    subtitle = subtitle ?: e.tag,
                    showTitle = true,
                    badges = badges,
                    actions = actions,
                    accentColorHint = e.accent,
                ),
        )
    }

    private fun wide(
        key: String,
        subtitle: String,
        progress: Double? = null,
        badges: List<CardBadge> = emptyList(),
        actions: List<CardAction> = listOf(CardAction.RESUME, CardAction.PLAY),
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
                    showProgress = progress != null,
                    progress = progress,
                    badges = badges,
                    actions = actions,
                    accentColorHint = e.accent,
                ),
        )
    }

    /** A single-element TIMELEFT badge list for a Continue Watching card's "N min left" chip. */
    private fun timeLeft(text: String): List<CardBadge> = listOf(CardBadge(kind = "TIMELEFT", text = text))

    /** A "Browse by Network" square wordmark tile (no catalog entry — purely a labeled provider). */
    private fun networkTile(name: String): RenderItem =
        RenderItem(
            media = MediaId(source = MediaSource.JELLYFIN, mediaType = MediaType.OTHER),
            card =
                CardDescriptor(
                    type = CardType.STUDIO,
                    aspectRatio = CardAspectRatio.SQUARE,
                    title = name,
                    showTitle = true,
                ),
        )

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

    /**
     * The single item of a [RowStyle.SPOTLIGHT] showcase row: a backdrop-led card with a synopsis
     * and a mixed badge strip.
     *
     * Two deliberate nulls exercise the card's fallbacks, since sample mode has no server behind it:
     *  - `logoImageUrl = null` → the styled-title treatment stands in for logo art.
     *  - award badges carry no `iconUrl` → [com.github.jkrishna289.orcax.ui.cards.EngineBadge]
     *    renders them as text pills, the same path a failed logo load takes.
     *
     * [trailerStreamUrl] is the only way a sample item can preview: these entries have no Jellyfin
     * or TMDB id, so the engine trailer seam can never resolve a URL for them. Left null by default
     * — set it to a reachable video URL to see the Spotlight trailer path end to end.
     */
    private fun spotlight(
        key: String,
        synopsis: String,
        badges: List<CardBadge> = emptyList(),
        trailerStreamUrl: String? = null,
    ): RenderItem {
        val e = entry(key)
        return RenderItem(
            media = mediaId(e),
            card =
                CardDescriptor(
                    type = CardType.SPOTLIGHT,
                    imageType = CardImageType.BACKDROP,
                    aspectRatio = CardAspectRatio.WIDE,
                    title = e.title,
                    subtitle = e.tag,
                    synopsis = synopsis,
                    showTitle = true,
                    wantsTrailer = true,
                    logoImageUrl = null,
                    trailerStreamUrl = trailerStreamUrl,
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
                        // Heterogeneous: a large Top Pick, a wide inline-trailer card, and posters
                        // carrying the promo / personalization badge treatments.
                        items =
                            listOf(
                                poster("loki", badges = listOf(CardBadge(kind = "TOP_PICK", text = "★ Top Pick")), size = CardSize.LARGE),
                                wide("mouse", subtitle = "Crime · Drama", actions = listOf(CardAction.PLAY, CardAction.DETAILS)),
                                poster("ember", badges = listOf(CardBadge(kind = "NEW_EPISODE", text = "New Episode"))),
                                poster("nocturne", badges = listOf(CardBadge(kind = "CONTEXT", text = "Based on Project Loki"))),
                                poster("verdict", badges = listOf(CardBadge(kind = "TRENDING", text = "▲ Trending"))),
                                poster("gumball"),
                                poster("dhindora"),
                            ),
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
                        // Resume banners with a time-left chip + glowing progress.
                        items =
                            listOf(
                                wide("mouse", subtitle = "S1 · E5", progress = 0.62, badges = timeLeft("24 min left")),
                                wide("euphoria", subtitle = "S2 · E3", progress = 0.28, badges = timeLeft("41 min left")),
                                wide("verdict", subtitle = "S1 · E8 · Almost done", progress = 0.84, badges = timeLeft("9 min left")),
                                wide("loki", subtitle = "S1 · E1", progress = 0.14, badges = timeLeft("47 min left")),
                                wide("silentbay", subtitle = "S1 · E4", progress = 0.48, badges = timeLeft("31 min left")),
                            ),
                    ),
                    // A cinematic showcase punctuating the feed. Spotlights sit DEEP in the home,
                    // never as the first content rows — the Billboard already owns the top, and
                    // stacking a second near-full-screen surface right beneath it leaves the user
                    // scrolling through two giant panels before reaching any browsable content.
                    // The engine picks both the item and the depth; several may appear per home.
                    RenderRow(
                        id = "spotlight_feature_1",
                        title = "In the Spotlight",
                        rowStyle = RowStyle.SPOTLIGHT,
                        items =
                            listOf(
                                spotlight(
                                    key = "loki",
                                    synopsis =
                                        "Loki and Lorelei crack puzzles, solve cases and uncover dark " +
                                            "secrets within the halls of Clark University — until one " +
                                            "case starts pointing back at them.",
                                    badges =
                                        listOf(
                                            // Award badges: no iconUrl in sample mode → text pills.
                                            CardBadge(kind = "AWARD", text = "Academy Award Winner"),
                                            CardBadge(kind = "AWARD", text = "3× Emmy Winner"),
                                            // Plain text badges.
                                            CardBadge(kind = "MATCH", text = "95% Match"),
                                            CardBadge(kind = "TRENDING", text = "Trending"),
                                            CardBadge(kind = "FORMAT", text = "4K HDR"),
                                            // Consumed by the rating row, not the strip.
                                            CardBadge(kind = "RATING", text = "9.4"),
                                            CardBadge(kind = "CERT", text = "TV-MA"),
                                            CardBadge(kind = "YEAR", text = "2026"),
                                        ),
                                ),
                            ),
                    ),
                    RenderRow(
                        id = "trending",
                        title = "Trending Now",
                        rowStyle = RowStyle.TOP10,
                        // Ranked cards (giant numeral beside the poster) with live viewer counts on
                        // the top three and a "Requested" glass badge on #4.
                        items =
                            listOf("verdict", "mouse", "euphoria", "loki", "ifwishes", "nocturne", "ember", "dhindora")
                                .mapIndexed { index, key ->
                                    val extra =
                                        when (index) {
                                            0 -> listOf(CardBadge(kind = "LIVE", text = "2.4k ▲"))
                                            1 -> listOf(CardBadge(kind = "LIVE", text = "1.8k ▲"))
                                            2 -> listOf(CardBadge(kind = "LIVE", text = "1.1k"))
                                            3 -> listOf(CardBadge(kind = "REQUESTED", text = "Requested"))
                                            else -> emptyList()
                                        }
                                    poster(
                                        key,
                                        badges = listOf(CardBadge(kind = "RANK", text = (index + 1).toString())) + extra,
                                        type = CardType.TOP_RANKED,
                                    )
                                },
                    ),
                    RenderRow(
                        id = "premieres",
                        title = "Premieres This Week",
                        rowStyle = RowStyle.STANDARD,
                        // A glowing TODAY premiere leads; the rest carry day-of-week chips, and one is
                        // still downloading.
                        items =
                            listOf(
                                poster(
                                    "loki",
                                    badges = listOf(CardBadge(kind = "TODAY", text = "Today"), CardBadge(kind = "PREMIERE", text = "Series Premiere")),
                                    size = CardSize.LARGE,
                                ),
                                poster("mouse", badges = listOf(CardBadge(kind = "DAY", text = "Tue"))),
                                poster("ifwishes", badges = listOf(CardBadge(kind = "DAY", text = "Thu"))),
                                poster(
                                    "ember",
                                    badges = listOf(CardBadge(kind = "DAY", text = "Fri"), CardBadge(kind = "DOWNLOADING", text = "Downloading")),
                                ),
                                poster("silentbay", badges = listOf(CardBadge(kind = "DAY", text = "Sat"))),
                            ),
                    ),
                    // A second showcase, deeper still — several spotlights per home is the norm,
                    // not a special case. Deliberately a different badge mix (no awards, a format
                    // logo slot, a "New Season" hook) so the strip's genericity is visible.
                    RenderRow(
                        id = "spotlight_feature_2",
                        title = "In the Spotlight",
                        rowStyle = RowStyle.SPOTLIGHT,
                        items =
                            listOf(
                                spotlight(
                                    key = "euphoria",
                                    synopsis =
                                        "A group of high-school students navigate love and friendship " +
                                            "in a world of drugs, trauma and social media.",
                                    badges =
                                        listOf(
                                            CardBadge(kind = "NEW_SEASON", text = "New Season"),
                                            CardBadge(kind = "MATCH", text = "88% Match"),
                                            CardBadge(kind = "FORMAT", text = "Dolby Vision"),
                                            CardBadge(kind = "RATING", text = "8.4"),
                                            CardBadge(kind = "CERT", text = "TV-MA"),
                                            CardBadge(kind = "YEAR", text = "2025"),
                                        ),
                                ),
                            ),
                    ),
                    RenderRow(
                        id = "networks",
                        title = "Browse by Network",
                        rowStyle = RowStyle.STANDARD,
                        items = listOf("APEX", "LUMEN", "NORTHBOX", "VELVET", "ORBIT", "CASCADE").map { networkTile(it) },
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
