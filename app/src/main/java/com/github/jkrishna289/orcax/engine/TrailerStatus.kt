package com.github.jkrishna289.orcax.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client mirror of the engine's trailer state machine (`GET /OrcaEngine/Trailer/{id}/status`). Lets
 * the client react to explicit lifecycle states instead of inferring intent from repeated HTTP 404s
 * (the old protocol). Wire values are the engine's PascalCase enum names; [EngineJson]'s
 * `coerceInputValues` maps any unknown value to [UNKNOWN] for forward-compatibility.
 */
@Serializable
enum class TrailerState {
    @SerialName("Unknown") UNKNOWN,
    @SerialName("NotAvailable") NOT_AVAILABLE,
    @SerialName("Discovering") DISCOVERING,
    @SerialName("Queued") QUEUED,
    @SerialName("Downloading") DOWNLOADING,
    @SerialName("Transcoding") TRANSCODING,
    @SerialName("Ready") READY,
    @SerialName("Playing") PLAYING,
    @SerialName("FailedTemporary") FAILED_TEMPORARY,
    @SerialName("FailedPermanent") FAILED_PERMANENT,
    @SerialName("Expired") EXPIRED,
}

/** One title to predictively prefetch (`POST /OrcaEngine/Trailer/Prefetch`). */
@Serializable
data class TrailerPrefetchItem(
    @SerialName("TmdbId") val tmdbId: Int,
    @SerialName("Type") val type: String,
)

/** Prefetch priority names understood by the engine (a subset of its TrailerPriority tiers). */
object TrailerPrefetchPriority {
    const val HERO_BILLBOARD = "HeroBillboard"
    const val LIKELY_NEXT = "LikelyNextCard"
    const val VISIBLE_ROW = "VisibleRow"
    const val DETAIL_PAGE = "DetailPage"
}

/** Response of `GET /OrcaEngine/Trailer/{id}/status`. */
@Serializable
data class TrailerStatus(
    @SerialName("State") val state: TrailerState = TrailerState.UNKNOWN,
    @SerialName("Cached") val cached: Boolean = false,
    @SerialName("Available") val available: Boolean = false,
    // Smart preview start offset in ms (Phase 14); null → the client uses its default skip.
    @SerialName("PreviewStartMs") val previewStartMs: Int? = null,
) {
    /** A playable file exists server-side right now. */
    val isReady: Boolean get() = cached || state == TrailerState.READY

    /** No trailer will ever be produced — stop asking. */
    val isPermanentlyUnavailable: Boolean
        get() = state == TrailerState.FAILED_PERMANENT || state == TrailerState.NOT_AVAILABLE

    /** A transient failure the server may recover from on retry. */
    val isTemporaryFailure: Boolean get() = state == TrailerState.FAILED_TEMPORARY
}
