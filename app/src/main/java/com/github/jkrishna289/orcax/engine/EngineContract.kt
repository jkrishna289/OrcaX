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
)

/** The `?supported=` query value (engine-side PascalCase enum names). */
const val SUPPORTED_CARD_TYPES_QUERY: String =
    "PosterPortrait,BannerWide,Episode,PersonCircle,Genre,Studio,Discover,Season,Hero,TopRanked"

/** Card contract version this app implements. */
const val CARD_CONTRACT_VERSION: Int = 1

/** Tolerant JSON for engine payloads (PascalCase keys + enum values via @SerialName above). */
val EngineJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
