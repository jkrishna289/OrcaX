package com.github.jkrishna289.orcax.ui.playback

/**
 * StreamLabelEngine
 *
 * Builds short, normalised labels for audio and subtitle streams.
 *
 * Strategy (priority order):
 *   1. Use structured Jellyfin fields directly — language (ISO code), codec,
 *      channelLayout, channels. These come straight from the container metadata
 *      and are always accurate. This is the same approach Radarr/Sonarr/Jellyfin
 *      use internally when they "know" the language without parsing titles.
 *   2. Fall back to parsing the display title string only when structured fields
 *      are missing.
 *
 * Output formats:
 *   • audioLabel()    → "<Language> <Channels> [Codec]"
 *                       e.g. "English 5.1 Dolby Atmos", "Italian DTS 5.1"
 *   • subtitleLabel() → "<Language>"
 *                       e.g. "English", "Italian"
 *                       "Forced" is NEVER shown per spec.
 */
object StreamLabelEngine {

    // ─── ISO 639-2/B + 2-letter codes → display names ────────────────────────
    // Covers the most common ~50 languages. Source: ISO 639.
    val LANGUAGE_CODES: Map<String, String> = mapOf(
        // English
        "en" to "English",  "eng" to "English",
        // Spanish
        "es" to "Spanish",  "spa" to "Spanish",
        // French
        "fr" to "French",   "fre" to "French",   "fra" to "French",
        // German
        "de" to "German",   "ger" to "German",   "deu" to "German",
        // Italian
        "it" to "Italian",  "ita" to "Italian",
        // Portuguese
        "pt" to "Portuguese", "por" to "Portuguese",
        // Russian
        "ru" to "Russian",  "rus" to "Russian",
        // Japanese
        "ja" to "Japanese", "jpn" to "Japanese",
        // Korean
        "ko" to "Korean",   "kor" to "Korean",
        // Chinese (Mandarin / Cantonese)
        "zh" to "Chinese",  "chi" to "Chinese",  "zho" to "Chinese",
        "cmn" to "Mandarin", "yue" to "Cantonese",
        // Arabic
        "ar" to "Arabic",   "ara" to "Arabic",
        // Hindi
        "hi" to "Hindi",    "hin" to "Hindi",
        // Bengali
        "bn" to "Bengali",  "ben" to "Bengali",
        // Tamil
        "ta" to "Tamil",    "tam" to "Tamil",
        // Telugu
        "te" to "Telugu",   "tel" to "Telugu",
        // Malayalam
        "ml" to "Malayalam","mal" to "Malayalam",
        // Kannada
        "kn" to "Kannada",  "kan" to "Kannada",
        // Marathi
        "mr" to "Marathi",  "mar" to "Marathi",
        // Urdu
        "ur" to "Urdu",     "urd" to "Urdu",
        // Punjabi
        "pa" to "Punjabi",  "pan" to "Punjabi",
        // Gujarati
        "gu" to "Gujarati", "guj" to "Gujarati",
        // Indonesian
        "id" to "Indonesian","ind" to "Indonesian",
        // Thai
        "th" to "Thai",     "tha" to "Thai",
        // Vietnamese
        "vi" to "Vietnamese","vie" to "Vietnamese",
        // Turkish
        "tr" to "Turkish",  "tur" to "Turkish",
        // Polish
        "pl" to "Polish",   "pol" to "Polish",
        // Dutch
        "nl" to "Dutch",    "dut" to "Dutch",    "nld" to "Dutch",
        // Swedish
        "sv" to "Swedish",  "swe" to "Swedish",
        // Norwegian
        "no" to "Norwegian","nor" to "Norwegian",
        // Danish
        "da" to "Danish",   "dan" to "Danish",
        // Finnish
        "fi" to "Finnish",  "fin" to "Finnish",
        // Czech
        "cs" to "Czech",    "cze" to "Czech",    "ces" to "Czech",
        // Greek
        "el" to "Greek",    "gre" to "Greek",    "ell" to "Greek",
        // Hebrew
        "he" to "Hebrew",   "heb" to "Hebrew",
        // Ukrainian
        "uk" to "Ukrainian","ukr" to "Ukrainian",
        // Romanian
        "ro" to "Romanian", "rum" to "Romanian", "ron" to "Romanian",
        // Hungarian
        "hu" to "Hungarian","hun" to "Hungarian",
        // Persian / Farsi
        "fa" to "Persian",  "per" to "Persian",  "fas" to "Persian",
        // Malay
        "ms" to "Malay",    "may" to "Malay",    "msa" to "Malay",
        // Catalan
        "ca" to "Catalan",  "cat" to "Catalan",
        // Czech
        "sk" to "Slovak",   "slk" to "Slovak",   "slo" to "Slovak",
        // Serbian
        "sr" to "Serbian",  "srp" to "Serbian",
        // Croatian
        "hr" to "Croatian", "hrv" to "Croatian",
        // Bulgarian
        "bg" to "Bulgarian","bul" to "Bulgarian",
        // Undetermined / Unknown — suppress
        "und" to "", "zxx" to "", "mul" to "",
    )

    // ─── Codec: raw container identifier → display label ─────────────────────
    // Used when stream.codec is set directly (most reliable).
    private val CODEC_FROM_ID: Map<String, String> = mapOf(
        "truehd"       to "TrueHD",
        "dtshd"        to "DTS-HD",
        "dts"          to "DTS",
        "eac3"         to "Dolby Digital+",
        "ac3"          to "Dolby Digital",
        "aac"          to "AAC",
        "flac"         to "FLAC",
        "opus"         to "Opus",
        "mp3"          to "MP3",
        "mp2"          to "MP2",
        "pcm_s16le"    to "PCM",
        "pcm_s24le"    to "PCM",
        "vorbis"       to "Vorbis",
        "wmav2"        to "WMA",
        "subrip"       to "",   // SRT subtitle codec — not shown for audio
        "ass"          to "",
        "dvd_subtitle" to "",
        "hdmv_pgs_subtitle" to "",
        "srt"          to "",
        "mov_text"     to "",
    )

    // Atmos / DTS:X are signalled in the display title / stream title, not in
    // the codec field (they're delivery formats layered on top of TrueHD/DTS-HD).
    private val CHANNEL_71 = Regex("""7[_. ]1""")
    private val CHANNEL_51 = Regex("""5[_. ]1""")
    private val ATMOS_HINT = Regex("""atmos""", RegexOption.IGNORE_CASE)
    private val DTSX_HINT  = Regex("""dts[:\-\s]?x""", RegexOption.IGNORE_CASE)
    private val DTSHD_HINT = Regex("""dts[\-\s]?hd""", RegexOption.IGNORE_CASE)
    private val TRUEHD_HINT = Regex("""true[\-\s]?hd""", RegexOption.IGNORE_CASE)

    // ─── Channel layout: string value from container ──────────────────────────
    private fun resolveChannels(layout: String?, count: Int?): String? {
        if (layout != null) {
            val l = layout.lowercase()
            return when {
                "7.1" in l || l.contains(CHANNEL_71) -> "7.1"
                "5.1" in l || l.contains(CHANNEL_51) -> "5.1"
                "4.0" in l                                  -> "4.0"
                "stereo" in l || "2.0" in l || "2ch" in l  -> "Stereo"
                "mono" in l || "1.0" in l                  -> "Mono"
                else -> null
            }
        }
        // Fall back to channel count integer
        return when (count) {
            8 -> "7.1"
            6 -> "5.1"
            2 -> "Stereo"
            1 -> "Mono"
            else -> null
        }
    }

    // ─── Language from structured field (priority 1) ─────────────────────────
    private fun languageFromCode(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val resolved = LANGUAGE_CODES[code.lowercase().trim()]
        return if (resolved != null && resolved.isEmpty()) null else resolved
    }

    // ─── Codec label from structured field (priority 1) ──────────────────────
    private fun codecFromStream(stream: SimpleMediaStream): String? {
        val title = (stream.streamTitle ?: "") + " " + stream.displayTitle
        // Atmos / DTS:X / DTS-HD are hinted in the title — check first
        return when {
            ATMOS_HINT.containsMatchIn(title) -> "Dolby Atmos"
            DTSX_HINT.containsMatchIn(title)  -> "DTS:X"
            DTSHD_HINT.containsMatchIn(title) -> "DTS-HD"
            TRUEHD_HINT.containsMatchIn(title) -> "TrueHD"
            else -> stream.codec?.let { CODEC_FROM_ID[it.lowercase()] }
        }
    }

    // ─── Fallback: extract language from raw title string ────────────────────
    // Used only when stream.language is null/unknown.
    private val NOISE_PATTERNS = listOf(
        Regex("""\b(default|original|dubbed|forced|sdh|cc|hi|hearing[\s\-]impaired|external|embedded|undefined|und|unknown|commentary|description|narration|foreign|full|subtitle[s]?|srt|pgs|ass)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\d+[\s\-]?bit\b""",         RegexOption.IGNORE_CASE),
        Regex("""\b\d+\s*hz\b""",               RegexOption.IGNORE_CASE),
        Regex("""\b\d+\s*kbps\b""",             RegexOption.IGNORE_CASE),
        Regex("""\([^)]*\)"""),
        Regex("""\[[^\]]*]"""),
    )

    // Codec and channel tokens for stripping during language extraction
    private val CODEC_TOKENS = listOf(
        "dolby atmos", "atmos", "truehd", "true hd", "dts:x", "dts-x", "dtsx",
        "dts-hd", "dts hd", "dts", "dolby digital plus", "dolby digital",
        "ddp", "dd+", "eac3", "ac3", "aac", "flac", "opus", "mp3", "pcm", "lpcm",
        "vorbis", "wma",
    ).sortedByDescending { it.length }  // longest first to avoid partial matches

    private val CHANNEL_TOKENS = listOf("7.1", "5.1", "4.0", "2.0", "1.0", "stereo", "mono")

    // Pre-compiled per-token patterns to avoid Regex construction on every call.
    private val CODEC_TOKEN_PATTERNS = CODEC_TOKENS.map {
        Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE)
    }
    private val CHANNEL_TOKEN_PATTERNS = CHANNEL_TOKENS.map {
        Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE)
    }

    private fun extractLanguageFromTitle(raw: String): String {
        var s = raw
        for (re in NOISE_PATTERNS) s = re.replace(s, " ")
        for (re in CODEC_TOKEN_PATTERNS) s = re.replace(s, " ")
        for (re in CHANNEL_TOKEN_PATTERNS) s = re.replace(s, " ")

        val words = s.split(Regex("""[\s\-_,;:/|]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any(Char::isLetter) }

        if (words.isEmpty()) return ""

        val first = words.first()
        // Try as an ISO code first
        languageFromCode(first)?.let { return it }

        // Try to resolve multi-word language ("Brazilian Portuguese")
        val second = words.getOrNull(1)
        if (second != null && second.all { it.isLetter() } && second.length in 3..12) {
            val combined = "${first.replaceFirstChar(Char::uppercaseChar)} ${second.replaceFirstChar(Char::uppercaseChar)}"
            if (combined.length <= 20) return combined
        }

        return first.replaceFirstChar(Char::uppercaseChar)
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Audio label: "<Language> <Channels> [Codec]"
     *
     * Uses Jellyfin's structured fields (language, codec, channelLayout/channels)
     * directly. Falls back to title parsing only when a field is missing.
     */
    fun audioLabel(stream: SimpleMediaStream): String {
        // 1. Language: ISO code (most reliable)
        val lang = languageFromCode(stream.language)
            ?: run {
                // Fall back to title parsing
                val raw = stream.displayTitle.ifBlank { stream.streamTitle ?: "" }
                extractLanguageFromTitle(raw).takeIf { it.isNotEmpty() } ?: "Unknown"
            }

        // 2. Channels: structured field, then title parsing
        val channels = resolveChannels(stream.channelLayout, stream.channels)
            ?: run {
                val raw = stream.displayTitle.ifBlank { stream.streamTitle ?: "" }
                val t = raw.lowercase()
                when {
                    "7.1" in t -> "7.1"
                    "5.1" in t -> "5.1"
                    "stereo" in t || "2.0" in t -> "Stereo"
                    "mono" in t -> "Mono"
                    else -> null
                }
            }

        // 3. Codec: Atmos/DTS:X from title hints, else codec field, else title scan
        val codec = codecFromStream(stream)
            ?: run {
                val raw = (stream.displayTitle + " " + (stream.streamTitle ?: "")).lowercase()
                when {
                    "eac3" in raw || "dd+" in raw || "dolby digital plus" in raw -> "Dolby Digital+"
                    "ac3" in raw || "dolby digital" in raw -> "Dolby Digital"
                    "aac" in raw -> "AAC"
                    "flac" in raw -> "FLAC"
                    "opus" in raw -> "Opus"
                    else -> null
                }
            }

        return buildString {
            append(lang)
            channels?.let { append(' '); append(it) }
            if (!codec.isNullOrBlank()) { append(' '); append(codec) }
        }
    }

    /**
     * Subtitle label: just "<Language>" — no Forced, SDH, Embedded, External.
     *
     * Uses the language ISO code directly (most reliable).
     * Never returns empty — falls back to "Unknown".
     */
    fun subtitleLabel(stream: SimpleMediaStream): String {
        // 1. ISO code (direct — always correct when set by Jellyfin)
        languageFromCode(stream.language)?.let { return it }

        // 2. Stream title (Jellyfin sometimes puts a clean name here like "English")
        if (!stream.streamTitle.isNullOrBlank()) {
            val fromTitle = extractLanguageFromTitle(stream.streamTitle)
            if (fromTitle.isNotEmpty()) return fromTitle
        }

        // 3. Display title (less clean but last resort)
        if (stream.displayTitle.isNotBlank()) {
            val fromDisplay = extractLanguageFromTitle(stream.displayTitle)
            if (fromDisplay.isNotEmpty()) return fromDisplay
        }

        return "Unknown"
    }
}