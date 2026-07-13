# AUTO Quality Engine — 28-Bug Audit

Reconstructed from the chat-only engineering audit (2026-07) so that "fixes #N" claims in
the quality redesign are reviewable. Code locations refer to commit `41509244`
(QualityManager.kt / QualityTier.kt / PlaybackViewModel.kt before the redesign).

Disposition legend:
- **FIX** — fixed directly by the redesign
- **GUARD** — the failure mode returns with the reactive rescue engine (product ruling 1)
  and is prevented by explicit guard windows
- **GONE** — the code path is hard-deleted with the adaptive switching machinery

| #  | Title | Where (pre-redesign) | Failure | Disposition |
|----|-------|----------------------|---------|-------------|
| 1  | Exo bandwidth listener never wired | PlaybackViewModel implements `AnalyticsListener` (registered :322) but never overrides `onBandwidthEstimate` | The best live bandwidth signal is discarded; AUTO relies solely on a one-shot probe | **FIX** — measurement store fed by `onBandwidthEstimate`, gated to direct play/stream (ruling 3) |
| 2  | Tier oscillation | `onPlaybackStats` / `onBandwidthMeasured` up/down switching with cooldowns (QualityManager :245–318) | Upgrade→rebuffer→downgrade→cooldown→upgrade loops on marginal links | **GONE** — one-way rescue ratchet per item; upgrades never happen mid-session |
| 3  | False downgrade at end of video | Buffer-based downgrade in `onPlaybackStats` (:262) | Forward buffer naturally drains near the item's end → "buffer critical" downgrade in the last seconds | **GUARD** — rescue ignores the final ~15 s of the item |
| 4  | False downgrade after seek | Same buffer trigger | Post-seek rebuffering counted as starvation → spurious downgrade | **GUARD** — 5–8 s post-seek grace window |
| 5  | LAN detection classifies the client, not the server | `detectNetworkType` (:486–509) checks the client's own link addresses | Client on private Wi-Fi + remote server over the internet ⇒ classified LAN ⇒ 0.90 safety factor on a WAN path | **FIX** — classification resolves `api.baseUrl` host address (RFC1918/ULA/loopback=LAN; CGNAT 100.64/10 & cellular=INTERNET) |
| 6  | Downgrade cascade during own switch | `triggerTierSwitch` → `forceSwitchRequired` → rebuild rebuffers → stats trigger again | One switch triggers another while the first rebuild is still priming | **GUARD** — rescue suspended while `isSwitchingStream` and until the buffer primes post-rebuild; max ~2 rescues per item |
| 7  | Probe rejects fast networks | `runSingleProbe` (:404) returns −1 when < 50 ms | On fast LAN the probe "fails", falls back to OS link speed → wrong tier on the best networks | **FIX** — never reject "too fast"; adaptive sizing (1 MB → 8 MB re-probe when < 300 ms) |
| 8  | Probe has no timeout | playback `okHttpClient` has no call timeout; blocking `execute()` in `withContext(IO)` | A stalled probe hangs the resolve indefinitely; `withTimeout` cannot interrupt the blocking socket read | **FIX** — `suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`, ~5 s/probe, ~12 s total |
| 9  | Concurrent probes race | `runSpeedTest` not single-flight | Item change or re-selection during a probe → two probes, last writer wins | **FIX** — single-flight probe Job, cancelled on new item/selection |
| 10 | Stale probe stamps the wrong item | No epoch/token on probe results; no synchronous state reset on new item | Movie A's probe result lands after switching to movie B and configures B's stream | **FIX** — epoch token; results from a stale epoch discarded; synchronous state reset before stream build |
| 11 | Subtitle penalty is a one-way mutation | `applySubtitlePenalty` (:219) mutates resolved tier; nothing restores it | Toggling PGS off never restores quality; penalty survives to unrelated streams | **FIX** — subtitle burn-in is a resolve-time modifier; recompute restores automatically |
| 12 | No peak-bitrate headroom in tier math | `fromMeasuredBps` compares average bitrates only | High-peak scenes exceed the chosen cap → rebuffering at a "safe" tier | **FIX** — required-for-original = (video + audio) × 1.2 peak headroom |
| 13 | Audio compatibility ignored (silent playback class) | Tier selection ignores source audio codec vs device support | TrueHD/Atmos stream-copied to a device that can't decode it → video plays, no audio | **FIX** — resolver audio-compat verdict; Original degraded/unavailable with re-encoded-audio recommendation |
| 14 | `allowAudioStreamCopy` tied to direct play only | `effectiveAllowAudioStreamCopy = effectiveEnableDirectPlay` (:132) | Transcoded video could still stream-copy incompatible audio (regression source of the silent-audio bug) | **FIX** — independent `audioStreamCopyAllowed` in PlaybackConstraints; transcode ⇒ false unless codec-compatible (regression test) |
| 15 | Quality choice not persisted | In-memory `persistedSeriesId`/`persistedTier` (:94–95) | Choice lost on app restart and when a movie interleaves between episodes | **FIX** — Room `QualityPreference` (userId + subjectId), manual picks only |
| 16 | Buffer-stat switching uses raw deltas without priming | `onPlaybackStats` counters | First stats window after (re)start reads garbage deltas → bogus switch decisions | **GONE** (switching deleted); tracker primes counters before deltas are trusted |
| 17 | Subtitle/audio change rebuild races | Subtitle change emitted `forceSwitchRequired` while `changeSubtitleStream` also rebuilt | Two rebuilds per toggle; racing `getPostedPlaybackInfo` calls | **FIX** — `changeSubtitleStream` owns the single rebuild; resolver modifier folds into it |
| 18 | `CancellationException` swallowed | Broad `catch (ex: Exception)` in probe/IO paths (:365, :411) | Cancelled coroutines log as "probe failed" and run fallback work after cancellation | **FIX** — rethrow `CancellationException` everywhere |
| 19 | Quality state mutated from multiple dispatchers | Fields written from IO probe, player thread stats, main-thread selection | Torn reads/lost updates on tier state | **FIX** — state confined to a single dispatcher |
| 20 | Upgrade cooldown constants mask instability | `upgradeDelayMs`/`DOWNGRADE_LOCK_MS` (:100–103) | Tuning constants paper over the oscillation instead of removing it | **GONE** — no upgrade path exists |
| 21 | Dead render/drop monitors | `monitorRendered`/`monitorDropped` lifetime counters in PlaybackViewModel | Lifetime (not delta) dropped-frame math → meaningless percentages; dead weight | **FIX** — deleted; PlaybackHealthTracker uses primed deltas |
| 22 | Monitoring loop entangled with switching | Loop feeds `onPlaybackStats` which both records and switches | Telemetry and control coupled; can't observe without acting | **FIX** — loop feeds the tracker only; RescueEngine decides separately |
| 23 | `onBandwidthMeasured` dead/vestigial path | QualityManager :293 — no live caller wired | Dead code carrying live-switch semantics waiting to be miswired | **GONE** |
| 24 | Probe re-runs for every item; skip too narrow | `runSpeedTest` per `onNewItem`; `alreadyOptimal` (:491) only covers direct-play | Repeated 5–12 s probes on the same network; needless rebuilds when transcoding at the same cap | **FIX** — measurement cache (server+network+fingerprint+user, TTL); skip extended to same-cap transcode |
| 25 | Double safety hedge | `safetyFactor(network) × 0.85` (QualityTier :79) | LAN: 0.90 × 0.85 = 0.765; INTERNET: 0.55 effective — systematic under-recommendation | **FIX** — single ×0.75 hedge |
| 26 | No metered-network policy | Probes run unconditionally | Multi-MB probes on hotspots/metered links burn user data | **FIX** — `isActiveNetworkMetered` ⇒ no probes; OS estimate + gated Exo only |
| 27 | "Direct Playback" tier lies | CINEMA hardcodes 25.4 Mbps + static "4K HDR" label (QualityTier :39–45) | Caps media above 25.4 Mbps (forces transcode while claiming direct play); label wrong for non-4K media | **FIX** — ORIGINAL = user pref cap only; labels computed from media |
| 28 | Tier selection blocks on the probe | `selectTier` path awaits `runSpeedTest` | UI stalls up to probe-timeout on selection | **FIX** — async selection; `isMeasuring` drives the spinner |

## Redesign coverage summary

- **FIX** (direct): 1, 5, 7–15, 17–19, 21–22, 24–28
- **GUARD** (rescue-engine guard windows per product ruling 1): 3, 4, 6
- **GONE** (adaptive machinery hard-deleted): 2, 16, 20, 23

Product rulings governing the redesign live in the approved plan
(`AUTO Quality Engine — Revised Plan`, 2026-07-13): reactive AUTO-only rescue with toast,
warm-cache pre-empt, hybrid fixed ladder, Exo samples gated to direct play,
health-history persistence deferred to v1.5.
