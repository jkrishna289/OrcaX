package com.github.jkrishna289.orcax.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.github.jkrishna289.orcax.preferences.AppPreference
import com.github.jkrishna289.orcax.preferences.AppPreferences
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.time.ExperimentalTime

/** Concurrent image requests allowed per host — raised from OkHttp's default of 5 for poster grids (#1). */
private const val IMAGE_MAX_REQUESTS_PER_HOST = 12

@Composable
fun CoilConfig(
    prefs: AppPreferences,
    okHttpClient: OkHttpClient,
    debugLogging: Boolean,
    enableCache: Boolean = true,
) = CoilConfig(
    diskCacheSizeBytes =
        prefs.advancedPreferences.imageDiskCacheSizeBytes.let {
            if (it < AppPreference.ImageDiskCacheSize.min * AppPreference.MEGA_BIT) {
                AppPreference.ImageDiskCacheSize.defaultValue * AppPreference.MEGA_BIT
            } else {
                it
            }
        },
    okHttpClient = okHttpClient,
    debugLogging = false,
    enableCache = true,
)

/**
 * Configure Coil image loading
 */
@OptIn(ExperimentalTime::class, ExperimentalCoilApi::class)
@Composable
fun CoilConfig(
    diskCacheSizeBytes: Long,
    okHttpClient: OkHttpClient,
    debugLogging: Boolean,
    enableCache: Boolean = true,
) {
    val client =
        remember(okHttpClient, debugLogging) {
            okHttpClient
                .newBuilder()
                // A home row shows a dozen-plus posters at once, all from the same host. OkHttp's
                // default cap of 5 requests/host serializes them, so the grid fills in slow waves
                // (#1). A dedicated dispatcher (sharing the connection pool) lets more load at once.
                .dispatcher(
                    Dispatcher().apply { maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST },
                ).apply {
                    if (debugLogging) {
                        addInterceptor {
                            val start = System.currentTimeMillis()
                            val req = it.request()
                            val res = it.proceed(req)
                            val time = System.currentTimeMillis() - start
                            Timber.v("${time}ms - ${req.url}")
                            res
                        }
                    }
                }.build()
        }
    setSingletonImageLoaderFactory { ctx ->
        Timber.i("Image diskCacheSizeBytes=$diskCacheSizeBytes")
        ImageLoader
            .Builder(ctx)
            .apply {
                if (enableCache) {
                    memoryCache(MemoryCache.Builder().maxSizePercent(ctx).build())
                    diskCache(
                        DiskCache
                            .Builder()
                            .directory(ctx.cacheDir.resolve("coil3_image_cache"))
                            .maxSizeBytes(diskCacheSizeBytes)
                            .build(),
                    )
                } else {
                    memoryCache(null)
                    diskCache(null)
                }
            }.crossfade(false)
            .logger(if (debugLogging) DebugLogger() else null)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        cacheStrategy = { OrcaCacheStrategy(CacheControlCacheStrategy()) },
                        callFactory = { client },
                    ),
                )
            }.build()
    }
}

/**
 * This [CacheStrategy] always prefers the cached response for Trickplay images,
 * otherwise the decision is delegated to the provided [CacheStrategy]
 *
 * The expectation is that Trickplay images will be prefetched so the cache will always be warm
 */
@OptIn(ExperimentalCoilApi::class)
private class OrcaCacheStrategy(
    private val delegate: CacheStrategy,
) : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult =
        if (networkRequest.url.contains("/Trickplay/")) {
            CacheStrategy.ReadResult(cacheResponse)
        } else {
            delegate.read(cacheResponse, networkRequest, options)
        }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)
}
