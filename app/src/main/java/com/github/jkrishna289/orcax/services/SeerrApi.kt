package com.github.jkrishna289.orcax.services

import com.github.jkrishna289.orcax.BuildConfig
import com.github.jkrishna289.orcax.api.seerr.SeerrApiClient
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.setup.seerr.createSeerrApiUrl
import okhttp3.OkHttpClient

/**
 * Wrapper for [SeerrApiClient]. In most cases, you should use [SeerrService] instead.
 */
class SeerrApi(
    private val okHttpClient: OkHttpClient,
) {
    var api: SeerrApiClient =
        SeerrApiClient(
            baseUrl = "",
            apiKey = null,
            okHttpClient = okHttpClient,
        )
        private set

    val active: Boolean get() = api.baseUrl.isNotNullOrBlank() && BuildConfig.DISCOVER_ENABLED

    fun update(
        baseUrl: String,
        apiKey: String?,
    ) {
        api = SeerrApiClient(createSeerrApiUrl(baseUrl), apiKey, okHttpClient)
    }
}
