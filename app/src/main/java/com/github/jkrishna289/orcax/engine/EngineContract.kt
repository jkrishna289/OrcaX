package com.github.jkrishna289.orcax.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client-side mirror of the Orca Engine's card/render contract (see the engine's `Contracts/`).
 *
 * IMPORTANT: Jellyfin's API (incl. plugin controllers) serializes **PascalCase** property names and
 * **dash-less GUIDs** (verified against the live server), so every property carries an explicit
 * [SerialName]. Enum wire values are PascalCase too. [EngineJson] stays tolerant for forward-compat.
 */

@Serializable
data class RenderBundle(
    @SerialName("ContractVersion") val contractVersion: Int = 1,
    @SerialName("Rows") val rows: List<RenderRow> = emptyList(),
)

/** One-call launch payload from the engine's `/Bootstrap` endpoint. */
@Serializable
data class BootstrapResponse(
    @SerialName("ContractVersion") val contractVersion: Int = 1,
    @SerialName("ServerTimeUtc") val serverTimeUtc: String? = null,
    @SerialName("Personalized") val personalized: Boolean = false,
    @SerialName("Settings") val settings: EngineSettings = EngineSettings(),
    @SerialName("Home") val home: RenderBundle = RenderBundle(),
)

/** Resolved remote config the engine sends in the bootstrap (admin + per-user layered settings). */
@Serializable
data class EngineSettings(
    @SerialName("Enabled") val enabled: Boolean = true,
    @SerialName("Features") val features: FeatureFlags = FeatureFlags(),
    @SerialName("DefaultRowSize") val defaultRowSize: Int = 20,
    @SerialName("SpotlightCount") val spotlightCount: Int = 5,
)

/** Engine feature flags (the client can adapt its UI without a redeploy). */
@Serializable
data class FeatureFlags(
    @SerialName("Personalization") val personalization: Boolean = true,
    @SerialName("Spotlight") val spotlight: Boolean = true,
    @SerialName("ContinueWatching") val continueWatching: Boolean = true,
    @SerialName("Trending") val trending: Boolean = true,
    @SerialName("SimilarityRows") val similarityRows: Boolean = true,
    @SerialName("JellyseerrDiscovery") val jellyseerrDiscovery: Boolean = false,
    @SerialName("Requests") val requests: Boolean = false,
    @SerialName("SourceStreaming") val sourceStreaming: Boolean = false,
    /**
     * Whether a streamed title can be kept permanently. Separate from [sourceStreaming]: keeping also
     * needs the operator to have configured a folder inside a Jellyfin library, and without one the
     * "Keep it?" prompt would be a dead end.
     */
    @SerialName("KeepStream") val keepStream: Boolean = false,
)

/**
 * One streamable source, already ranked and described by the engine.
 *
 * [summary] and [quality] are the plain-language strings meant for display; everything below them
 * ([seeders], [videoCodec], [tier], [indexer], [title]) is technical detail that belongs behind an
 * "advanced" affordance, per the design rule that a viewer should never need to know what a seeder is.
 */
@Serializable
data class TorrentSourceDto(
    @SerialName("Title") val title: String = "",
    /**
     * Opaque handle used to open a stream. Deliberately not a magnet or URL: indexer download links
     * embed the server's API key, so the engine keeps them and resolves this id server-side.
     */
    @SerialName("Id") val id: String = "",
    @SerialName("SizeBytes") val sizeBytes: Long = 0,
    @SerialName("Seeders") val seeders: Int = 0,
    @SerialName("Leechers") val leechers: Int = 0,
    @SerialName("Indexer") val indexer: String? = null,
    @SerialName("ResolutionHeight") val resolutionHeight: Int = 0,
    @SerialName("Tier") val tier: String? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("Hdr") val hdr: Boolean = false,
    @SerialName("DolbyVision") val dolbyVision: Boolean = false,
    @SerialName("Audio") val audio: String? = null,
    @SerialName("ReleaseGroup") val releaseGroup: String? = null,
    @SerialName("Quality") val quality: String = "",
    @SerialName("Summary") val summary: String = "",
)

/**
 * Sources bucketed into the few choices a viewer is offered. Any bucket can be null when nothing
 * qualifies; [all] is empty when the search found nothing usable.
 */
@Serializable
data class SourceGroups(
    @SerialName("Recommended") val recommended: TorrentSourceDto? = null,
    @SerialName("BestQuality") val bestQuality: TorrentSourceDto? = null,
    @SerialName("FastestStart") val fastestStart: TorrentSourceDto? = null,
    @SerialName("LowestBandwidth") val lowestBandwidth: TorrentSourceDto? = null,
    @SerialName("FourKHdr") val fourKHdr: TorrentSourceDto? = null,
    @SerialName("All") val all: List<TorrentSourceDto> = emptyList(),
)

/**
 * Body for `POST /Stream/Sessions`. Supply [sourceId] from a prior search, or [magnet] to stream a
 * magnet link directly (the debug path). Season/episode only matter for a season-pack source.
 */
@Serializable
data class StreamSessionBody(
    @SerialName("SourceId") val sourceId: String? = null,
    @SerialName("Magnet") val magnet: String? = null,
    @SerialName("Season") val season: Int? = null,
    @SerialName("Episode") val episode: Int? = null,
)

/**
 * An open torrent stream. [path] is server-relative — the client joins it to the Jellyfin base URL
 * to get the URL the player fetches. [token] is a capability: it is the only thing authorizing reads,
 * so it must not be logged or shared.
 */
@Serializable
data class StreamSessionResult(
    @SerialName("Token") val token: String = "",
    @SerialName("Path") val path: String = "",
    @SerialName("FileName") val fileName: String = "",
    @SerialName("Length") val length: Long = 0,
    @SerialName("MediaInfo") val mediaInfo: StreamMediaInfo? = null,
    /** `Preparing` on creation — the session is returned before the swarm has been found. */
    @SerialName("State") val state: String = "",
)

/**
 * A live report on one stream session: where it has got to, and how its swarm is actually performing.
 *
 * Every number is measured server-side off the torrent handle, never estimated. The connecting screen
 * prints them while the viewer waits, so a fabricated value would be a visible lie — and the whole
 * reason this endpoint exists is that a wait with no information is the worst wait there is.
 */
@Serializable
data class StreamSessionStatus(
    @SerialName("State") val state: String = "",
    @SerialName("FailureReason") val failureReason: String? = null,
    @SerialName("FileName") val fileName: String = "",
    @SerialName("Length") val length: Long = 0,
    @SerialName("MediaInfo") val mediaInfo: StreamMediaInfo? = null,
    @SerialName("Peers") val peers: Int = 0,
    @SerialName("Seeds") val seeds: Int = 0,
    @SerialName("Leechers") val leechers: Int = 0,
    @SerialName("DownloadRateBytesPerSecond") val downloadRateBps: Long = 0,
    @SerialName("DownloadedBytes") val downloadedBytes: Long = 0,
    @SerialName("Progress") val progress: Double = 0.0,
    @SerialName("TorrentState") val torrentState: String = "",
    @SerialName("HasMetadata") val hasMetadata: Boolean = false,
) {
    val isReady: Boolean get() = state.equals("Ready", ignoreCase = true)
    val isFailed: Boolean get() = state.equals("Failed", ignoreCase = true)
}

/**
 * Container facts the engine read out of the torrent with ffprobe. Null on the parent when the probe
 * couldn't run — the player then discovers tracks itself, which is the pre-probe behaviour.
 */
@Serializable
data class StreamMediaInfo(
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Streams") val streams: List<StreamTrack> = emptyList(),
)

/** One track inside a torrent's video file. [index] is the container's own index, passed through. */
@Serializable
data class StreamTrack(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("ChannelLayout") val channelLayout: String? = null,
    @SerialName("BitRate") val bitRate: Int? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("ColorTransfer") val colorTransfer: String? = null,
    @SerialName("ColorPrimaries") val colorPrimaries: String? = null,
)

/** Result of an engine-proxied request (`POST /Requests`). */
@Serializable
data class RequestResult(
    @SerialName("Outcome") val outcome: String = "",
    @SerialName("Success") val success: Boolean = false,
    @SerialName("Availability") val availability: AvailabilityState = AvailabilityState.UNAVAILABLE,
    @SerialName("Message") val message: String = "",
)

/** Body for `POST /Requests` (engine binds these case-insensitively; PascalCase to be safe). */
@Serializable
data class EngineRequestBody(
    @SerialName("UserId") val userId: String,
    @SerialName("TmdbId") val tmdbId: Int,
    @SerialName("MediaType") val mediaType: String,
    @SerialName("Title") val title: String? = null,
)

/** Response of `GET /Similar` — a directly-renderable "More Like This" row. */
@Serializable
data class SimilarResponse(
    @SerialName("SourceCatalogId") val sourceCatalogId: Long = 0,
    @SerialName("Row") val row: RenderRow = RenderRow(),
)

/** Response of `GET /Metadata/Trivia` — "Did You Know?" facts for an item (lazy, cached server-side). */
@Serializable
data class TriviaResponse(
    @SerialName("CatalogId") val catalogId: Long = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("Facts") val facts: List<String> = emptyList(),
)

/** Response of `GET /Metadata/Warnings` — content advisories for a title (per movie/series, Groq-generated, cached). */
@Serializable
data class ContentWarningsResponse(
    @SerialName("CatalogId") val catalogId: Long = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("HasWarnings") val hasWarnings: Boolean = false,
    @SerialName("Summary") val summary: String = "",
    @SerialName("Warnings") val warnings: List<ContentWarning> = emptyList(),
)

/** A single content advisory (category + severity + spoiler-free note), in fixed safety-first order. */
@Serializable
data class ContentWarning(
    @SerialName("Category") val category: String = "",
    @SerialName("Severity") val severity: String = "moderate",
    @SerialName("Note") val note: String = "",
)

/** Body for `POST /Behavior/Feedback` — an explicit thumbs up/down (a strong personalization signal). */
@Serializable
data class FeedbackBody(
    @SerialName("UserId") val userId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("ThumbsUp") val thumbsUp: Boolean,
)

/** Response of `GET /Requests/Status` — current availability for a requested TMDB title. */
@Serializable
data class AvailabilityStatusResponse(
    @SerialName("TmdbId") val tmdbId: Int = 0,
    @SerialName("MediaType") val mediaType: MediaType = MediaType.UNKNOWN,
    @SerialName("Configured") val configured: Boolean = false,
    @SerialName("Availability") val availability: AvailabilityState? = null,
)

/** A single home-telemetry event (Feature A) sent to `POST /Behavior/Events`. */
@Serializable
data class TelemetryEvent(
    @SerialName("ItemId") val itemId: String,
    @SerialName("EventType") val eventType: String,
    @SerialName("Value") val value: Double = 0.0,
    @SerialName("Context") val context: String? = null,
)

/** A batch of telemetry events for one user. */
@Serializable
data class TelemetryBatch(
    @SerialName("UserId") val userId: String,
    @SerialName("Events") val events: List<TelemetryEvent>,
)

@Serializable
data class RenderRow(
    @SerialName("Id") val id: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("RowStyle") val rowStyle: RowStyle = RowStyle.STANDARD,
    @SerialName("Items") val items: List<RenderItem> = emptyList(),
)

@Serializable
data class RenderItem(
    @SerialName("Media") val media: MediaId = MediaId(),
    @SerialName("Card") val card: CardDescriptor = CardDescriptor(),
)

@Serializable
data class MediaId(
    @SerialName("Source") val source: MediaSource = MediaSource.JELLYFIN,
    @SerialName("JellyfinId") val jellyfinId: String? = null,
    @SerialName("TmdbId") val tmdbId: Int? = null,
    @SerialName("MediaType") val mediaType: MediaType = MediaType.UNKNOWN,
    @SerialName("Availability") val availability: AvailabilityState = AvailabilityState.UNAVAILABLE,
)

@Serializable
data class CardDescriptor(
    @SerialName("Type") val type: CardType = CardType.POSTER_PORTRAIT,
    @SerialName("ImageType") val imageType: CardImageType = CardImageType.PRIMARY,
    @SerialName("AspectRatio") val aspectRatio: CardAspectRatio = CardAspectRatio.TALL,
    @SerialName("Size") val size: CardSize = CardSize.STANDARD,
    @SerialName("ImageUrl") val imageUrl: String? = null,
    @SerialName("BackdropImageUrl") val backdropImageUrl: String? = null,
    @SerialName("LogoImageUrl") val logoImageUrl: String? = null,
    @SerialName("TrailerStreamUrl") val trailerStreamUrl: String? = null,
    @SerialName("TrailerStartOffsetMs") val trailerStartOffsetMs: Int = 0,
    @SerialName("AutoPlayDelayMs") val autoPlayDelayMs: Int = 0,
    @SerialName("WantsTrailer") val wantsTrailer: Boolean = false,
    @SerialName("Title") val title: String? = null,
    @SerialName("Subtitle") val subtitle: String? = null,
    @SerialName("Synopsis") val synopsis: String? = null,
    @SerialName("ShowTitle") val showTitle: Boolean = true,
    @SerialName("ShowProgress") val showProgress: Boolean = false,
    @SerialName("ShowWatched") val showWatched: Boolean = true,
    @SerialName("ShowFavorite") val showFavorite: Boolean = true,
    @SerialName("Progress") val progress: Double? = null,
    @SerialName("Badges") val badges: List<CardBadge> = emptyList(),
    @SerialName("Actions") val actions: List<CardAction> = emptyList(),
    @SerialName("AccentColorHint") val accentColorHint: String? = null,
)

@Serializable
data class CardBadge(
    @SerialName("Kind") val kind: String = "",
    @SerialName("Text") val text: String? = null,
    /** Optional icon (e.g. cached studio/provider logo). Server-relative (`/OrcaEngine/Images/…`) — resolve against the server base URL. Null → render [text] as a pill. */
    @SerialName("IconUrl") val iconUrl: String? = null,
)

@Serializable
data class ClientCapabilities(
    @SerialName("CardContractVersion") val cardContractVersion: Int = 1,
    @SerialName("SupportedCardTypes") val supportedCardTypes: List<CardType> = emptyList(),
    @SerialName("SupportsInlineVideo") val supportsInlineVideo: Boolean = false,
)

@Serializable
enum class MediaSource {
    @SerialName("Jellyfin") JELLYFIN,
    @SerialName("Tmdb") TMDB,
}

@Serializable
enum class MediaType {
    @SerialName("Unknown") UNKNOWN,
    @SerialName("Movie") MOVIE,
    @SerialName("Series") SERIES,
    @SerialName("Season") SEASON,
    @SerialName("Episode") EPISODE,
    @SerialName("Person") PERSON,
    @SerialName("Collection") COLLECTION,
    @SerialName("Other") OTHER,
}

@Serializable
enum class AvailabilityState {
    @SerialName("WatchNow") WATCH_NOW,
    @SerialName("Request") REQUEST,
    @SerialName("Requested") REQUESTED,
    @SerialName("Downloading") DOWNLOADING,
    @SerialName("RecentlyAdded") RECENTLY_ADDED,
    @SerialName("Unavailable") UNAVAILABLE,
}

@Serializable
enum class CardType {
    @SerialName("PosterPortrait") POSTER_PORTRAIT,
    @SerialName("BannerWide") BANNER_WIDE,
    @SerialName("Episode") EPISODE,
    @SerialName("PersonCircle") PERSON_CIRCLE,
    @SerialName("Genre") GENRE,
    @SerialName("Studio") STUDIO,
    @SerialName("Discover") DISCOVER,
    @SerialName("Season") SEASON,
    @SerialName("Hero") HERO,
    @SerialName("TopRanked") TOP_RANKED,
    @SerialName("Logo") LOGO,
    @SerialName("NowPlaying") NOW_PLAYING,

    /** The single item of a [RowStyle.SPOTLIGHT] row — a near-full-screen cinematic showcase. */
    @SerialName("Spotlight") SPOTLIGHT,
}

@Serializable
enum class CardImageType {
    @SerialName("Primary") PRIMARY,
    @SerialName("Backdrop") BACKDROP,
    @SerialName("Thumb") THUMB,
    @SerialName("Logo") LOGO,
}

@Serializable
enum class CardAspectRatio {
    @SerialName("Tall") TALL,
    @SerialName("Wide") WIDE,
    @SerialName("Square") SQUARE,
    @SerialName("FourThree") FOUR_THREE,
}

@Serializable
enum class CardSize {
    @SerialName("Standard") STANDARD,
    @SerialName("Large") LARGE,
}

@Serializable
enum class CardAction {
    @SerialName("Play") PLAY,
    @SerialName("Resume") RESUME,
    @SerialName("Request") REQUEST,
    @SerialName("Details") DETAILS,
}

@Serializable
enum class RowStyle {
    @SerialName("Standard") STANDARD,
    @SerialName("Hero") HERO,
    @SerialName("Top10") TOP10,
    @SerialName("Circle") CIRCLE,

    /**
     * A dedicated cinematic showcase: **exactly one** item rendered nearly full-screen, which plays
     * its trailer inline on focus. Unlike every other style this one *is* routing — it selects a
     * different composable ([com.github.jkrishna289.orcax.ui.cards.SpotlightCard]) rather than a
     * carousel of cards.
     *
     * **Several Spotlight rows per home are expected**, punctuating the feed at engine-chosen
     * depths rather than appearing in fixed positions. Two placement rules:
     *  - Each row needs a **unique id** — the home renders rows keyed by id, so duplicates break it.
     *  - They belong **deep in the feed, not as the first content rows**. The Billboard already owns
     *    the top; a near-full-screen showcase directly beneath it makes the user scroll past two
     *    giant panels before reaching anything browsable.
     *
     * Distinct from the legacy hero row (id `"spotlight"`, consumed by the Billboard): a Spotlight
     * row must not use that id, or the home view model's hero split will swallow it.
     */
    @SerialName("Spotlight") SPOTLIGHT,
}

/**
 * Card types this app build can actually render. Advertised to the engine so it only
 * emits renderable cards (reserved types like Hero/TopRanked fall back server-side).
 */
val SUPPORTED_CARD_TYPES: List<CardType> = listOf(
    CardType.POSTER_PORTRAIT,
    CardType.BANNER_WIDE,
    CardType.EPISODE,
    CardType.PERSON_CIRCLE,
    CardType.GENRE,
    CardType.STUDIO,
    CardType.DISCOVER,
    CardType.SEASON,
    CardType.HERO,
    CardType.TOP_RANKED,
    CardType.SPOTLIGHT,
)

/**
 * The `?supported=` query value (engine-side PascalCase enum names). **Derived** from
 * [SUPPORTED_CARD_TYPES] via each entry's `@SerialName`, so adding a card type in one place can
 * never leave the advertised list silently out of date.
 */
val SUPPORTED_CARD_TYPES_QUERY: String =
    SUPPORTED_CARD_TYPES.joinToString(",") { type ->
        CardType.serializer().descriptor.getElementName(type.ordinal)
    }

/** Card contract version this app implements. */
const val CARD_CONTRACT_VERSION: Int = 1

/** Tolerant JSON for engine payloads (PascalCase keys + enum values via @SerialName above). */
val EngineJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
