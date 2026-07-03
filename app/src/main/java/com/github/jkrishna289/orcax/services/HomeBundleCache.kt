package com.github.jkrishna289.orcax.services

import android.content.Context
import com.github.jkrishna289.orcax.engine.EngineJson
import com.github.jkrishna289.orcax.engine.RenderBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny persistent cache of the last successful engine home [RenderBundle], keyed per user, so the
 * home paints instantly on open while a fresh bundle is fetched in the background
 * (stale-while-revalidate).
 *
 * The bundle is a few KB of JSON, so there is no size cap — it's a single last-good entry per user.
 * Image bytes (the heavy part) are cached separately by Coil, whose disk budget the user configures
 * via `AppPreference.ImageDiskCacheSize`.
 */
@Singleton
class HomeBundleCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dir = File(context.filesDir, DIR_NAME)

        private fun file(userId: UUID?) = File(dir, "home_${userId?.toString() ?: ANON}.json")

        /** The cached bundle for [userId], or null when absent/unreadable. */
        suspend fun read(userId: UUID?): RenderBundle? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = file(userId)
                    if (!f.exists()) return@runCatching null
                    EngineJson.decodeFromString<RenderBundle>(f.readText())
                }.onFailure { Timber.w(it, "Home bundle cache read failed") }
                    .getOrNull()
            }

        /** Persists [bundle] as the last-good home for [userId]. Failures are logged, not thrown. */
        suspend fun write(
            userId: UUID?,
            bundle: RenderBundle,
        ) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!dir.exists()) dir.mkdirs()
                    file(userId).writeText(EngineJson.encodeToString(bundle))
                }.onFailure { Timber.w(it, "Home bundle cache write failed") }
            }
        }

        /** Drops the cached home for [userId] (e.g. when the engine is disabled server-side). */
        suspend fun clear(userId: UUID?) {
            withContext(Dispatchers.IO) { runCatching { file(userId).delete() } }
        }

        companion object {
            private const val DIR_NAME = "home_bundle_cache"
            private const val ANON = "anon"
        }
    }
