# Spotlight & Awards — Engine Specification

Engine-side work for the Spotlight showcase, to be implemented in `C:\wholphine-Engine-Plugin`
(the Jellyfin plugin repo), **not** in this Android repo.

The Android client half shipped first and is already on `main`. This document describes what the
engine must produce for that client to light up. Nothing here is implemented yet.

**Boundary rule, non-negotiable:** the Android app never calls an external award API. It consumes
Orca Engine responses only. Every provider credential, rate limit, retry and cache lives server-side.

---

## 1. What the client already does

Implemented and compiling in the app today, so the engine can be built against a fixed target:

| Client capability | Where | Engine-visible contract |
|---|---|---|
| Routes a row to the cinematic showcase | `DynamicCardRow` | `RowStyle.SPOTLIGHT` (`"Spotlight"`) |
| Renders the single showcase item | `ui/cards/SpotlightCard.kt` | `CardType.SPOTLIGHT` (`"Spotlight"`) |
| Renders image **or** text badges generically | `ui/cards/BadgeStrip.kt` | `CardBadge.iconUrl` present → image; absent → text pill |
| Resolves engine-served images | `ImageUrlService.engineImageUrl` | Any server-relative path, e.g. `/OrcaEngine/Images/Award/{id}` |
| Advertises what it can render | `SUPPORTED_CARD_TYPES_QUERY` | `?supported=…,Spotlight` |

Three details the engine implementer will trip on if unaware:

1. **The row id must not be `"spotlight"`, and every Spotlight row needs a unique id.**
   `EngineHomeViewModel.applyBundle()` pulls the row whose id is literally `"spotlight"` into
   `heroes` (the Billboard) and renders only the remainder, so that id would be swallowed. The home
   also renders rows keyed by id, so two Spotlights sharing an id break the list. The sample bundle
   uses `"spotlight_feature_1"` / `"spotlight_feature_2"`.
2. **Exactly one item per row** — but **many rows per home**. `SpotlightCard` renders
   `row.items.first()`; extras are silently dropped, and an empty list renders nothing rather than
   crashing. Multiple showcases are the norm; see §6 for placement.
3. **Badge kinds the strip drops.** `RATING`, `SCORE`, `CERT`, `RATED`, `CERTIFICATE`, `YEAR`,
   `DATE` are consumed by the card's own rating row; `GENRE` is dropped as noise. Send them — they
   are used — just don't expect them as pills.

`CardBadge` needs **no schema change**: `iconUrl` already exists.

---

## 2. Provider abstraction

Wikidata is the first implementation, not the design. The resolver must never leak a source
assumption into storage, the card contract, or the client.

```
interface IAwardProvider
{
    string Name { get; }
    int Priority { get; }                     // lower wins in a merge conflict
    Task<IReadOnlyList<AwardRecord>> GetAwardsAsync(ProviderIds ids, CancellationToken ct);
}
```

| Implementation | Source | Notes |
|---|---|---|
| `WikidataAwardProvider` | Wikidata SPARQL | First shipped impl. Rich award/ceremony modelling, no API key, generous limits. |
| `ImdbAwardProvider` | IMDb | Best coverage; licensing must be checked before shipping. |
| `TmdbAwardProvider` | TMDb | Already a dependency elsewhere in the engine; thin award data. |
| `ManualOverrideProvider` | Engine-local | Highest priority. Curator fixes and anything the automated chain gets wrong. |

`AwardResolver` fans out across registered providers, merges by a stable award identity
(ceremony + year + category), and resolves conflicts by `Priority`. A provider that throws or times
out is skipped, never fatal — a missing award badge must never fail a home bundle.

`AwardRecord` carries at minimum: stable id, display text, ceremony, year, category, won-vs-nominated,
and a source-logo URL for the fetch step.

---

## 3. Storage ownership

**Orca Engine owns this data. Jellyfin's core database is not touched.**

Jellyfin stays responsible for media metadata only. The engine owns its own store for award metadata,
the logo asset cache, and — as they land — popularity, embeddings and recommendation vectors. This
keeps the plugin uninstallable without leaving orphaned rows in the host schema, and lets the award
cache be invalidated independently of a library refresh.

Two stores:

- **Award metadata** — resolved `AwardRecord`s keyed by item, with a resolved-at timestamp and the
  winning provider name (needed to debug a wrong badge later).
- **Logo assets** — binary cache keyed by award id, serving the transparent artwork.

---

## 4. Trigger and pipeline

Hook: library item add and metadata refresh (`ItemAdded` / the metadata-refresh path).

```
item added/refreshed
  └─ resolve provider ids (tmdb/imdb) from the Jellyfin item
     └─ AwardResolver.GetAwards(ids)          // provider chain, merged by priority
        └─ for each award without a cached logo:
           fetch official transparent logo    // SVG or hi-res transparent PNG
           └─ store in engine logo cache
```

**Logo asset requirements.** Logo-type artwork only — transparent background, *not* a rectangular
promotional still. The client draws image badges with no chip behind them (unlike `StudioBadge`,
which puts provider logos on a dark chip because those are light-on-transparent); a rectangular
asset will look broken. Prefer SVG, else a hi-res transparent PNG.

Resolution must be asynchronous and idempotent, and must never block the item-added hook or a home
bundle response. A title with unresolved awards simply ships without award badges that round.

---

## 5. Serving

**Images.** Stable path `/OrcaEngine/Images/Award/{id}`, served by the existing engine image service.
The client resolves it through `engineImageUrl` against the server base URL, so no absolute URLs are
needed in the payload (absolute URLs pass through unchanged if you send them anyway).

**Badges.** Attach to the Spotlight item:

```json
{ "Kind": "AWARD", "Text": "Academy Award Winner", "IconUrl": "/OrcaEngine/Images/Award/oscar-bp-2026" }
```

`Text` is required even when `IconUrl` is set — the client falls back to it while the image loads
and if it fails, which is also the only thing that renders in sample mode.

The strip is generic, so the same mechanism carries format and personalization badges with no client
change: `4K HDR`, `Dolby Vision`, `95% Match`, `Trending`, `New Season`, `Because You Watched …` —
with or without artwork.

**Row.**

```json
{ "Id": "spotlight_feature_1", "Title": "In the Spotlight", "RowStyle": "Spotlight", "Items": [ … ] }
```

### ⚠ Feature-flag collision — decide before implementing

The brief says to gate this on the existing `FeatureFlags.Spotlight` / `EngineSettings.SpotlightCount`.
Those already gate the **legacy hero row** that feeds the Billboard, and `SpotlightCount` defaults to
`5` — meaningless for a row that must hold exactly one item. Reusing them makes the two features
impossible to configure independently and would let someone set a count the showcase silently ignores.

Recommendation: add `FeatureFlags.SpotlightFeature` (bool) for the showcase and leave the existing
pair owning the Billboard hero set. If the flags are reused instead, `SpotlightCount` must be
documented as ignored for `RowStyle.SPOTLIGHT`. Either way both the engine and the app's contract
mirror in `engine/EngineContract.kt` need the same decision applied.

Note the app's `RowStyle`/`CardType` mirrors already accept `"Spotlight"`, but the flag change above
is **not** yet in `FeatureFlags` — that is client work this session did not do.

---

## 6. Selection **and placement** are the engine's job

The client displays whatever the engine selects, wherever the engine puts it. There is no "highest
rated" rule, and the client must never acquire one.

Selection is deliberately left open so it can become AI-driven: match score, watch history,
freshness, completion probability, diversity against the rest of the home, time of day. The award
pipeline feeds *presentation*, not selection — an item does not earn the Spotlight by having awards.

**Placement is equally the engine's call, with two product constraints:**

- **Several Spotlights per home**, not one. They punctuate the feed — a cinematic beat between
  carousels — rather than acting as a single hero slot. The Billboard is the one-per-home surface;
  the Spotlight is not.
- **Never the first content rows.** The Billboard already occupies the top of the screen. Emitting a
  Spotlight as row 1, 2 or 3 stacks a second near-full-screen panel directly under it, so the user
  scrolls through two giant surfaces before reaching anything browsable. Place them deeper, and vary
  the depth between homes rather than pinning a fixed index.

The client enforces neither constraint — it renders a Spotlight wherever it appears in `rows`. They
are engine-side product rules, which is why they live in this document.

---

## 7. Open questions

| # | Question | Blocking? |
|---|---|---|
| 1 | Reuse `FeatureFlags.Spotlight` or add `SpotlightFeature`? (§5) | Yes — changes both repos |
| 2 | IMDb licensing for `ImdbAwardProvider` | No — Wikidata ships first |
| 3 | Award cache invalidation policy (awards are near-immutable; ceremonies are annual) | No |
| 4 | Does the Spotlight item need a trailer guarantee, or is artwork-only acceptable? | No — client degrades to artwork |
| 5 | Should `CardDescriptor.TrailerStreamUrl` be populated for Spotlight items? | See below |

On (5): `TrailerStreamUrl` existed in the contract but was **read by nothing** until this session.
`SpotlightCard` now prefers it over the engine trailer seam, so populating it is an option for items
where the engine already knows a direct URL. Leaving it null keeps the existing status-gated
`/OrcaEngine/Trailer` path, which is the expected default.
