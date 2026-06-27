package com.github.jkrishna289.orcax.services.hilt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.work.WorkManager
import com.github.jkrishna289.orcax.BuildConfig
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.preferences.updateInterfacePreferences
import com.github.jkrishna289.orcax.services.SeerrApi
import com.github.jkrishna289.orcax.util.CoroutineContextApiClientFactory
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.RememberTabManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * An [OkHttpClient] that includes the user's access token when making requests
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

/**
 * A basic [OkHttpClient] that does not include auth
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardOkHttpClient

/**
 * A [CoroutineScope] with [Dispatchers.IO]
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoCoroutineScope

/**
 * A [CoroutineScope] with [Dispatchers.Default]
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultCoroutineScope

/**
 * [Dispatchers.IO]
 *
 * @see IoCoroutineScope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * [Dispatchers.Default]
 *
 * @see DefaultCoroutineScope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clientInfo(
        @ApplicationContext context: Context,
    ): ClientInfo =
        ClientInfo(
            name = context.getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
        )

    // ── StandardOkHttpClient: no auth headers, used for speed tests & Seerr ──
    @StandardOkHttpClient
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .apply {
                // TODO user agent, timeouts, logging, etc
            }.build()

    // ── AuthOkHttpClient: injects Jellyfin auth header automatically ──────────
    @AuthOkHttpClient
    @Provides
    @Singleton
    fun authOkHttpClient(
        serverRepository: ServerRepository,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) = okHttpClient
        .newBuilder()
        .addInterceptor {
            val request = it.request()
            val newRequest =
                serverRepository.currentUser.value?.accessToken?.let { token ->
                    request
                        .newBuilder()
                        .addHeader(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = clientInfo.name,
                                clientVersion = clientInfo.version,
                                deviceId = deviceInfo.id,
                                deviceName = deviceInfo.name,
                                accessToken = token,
                            ),
                        ).build()
                }
            it.proceed(newRequest ?: request)
        }.build()

    @Provides
    @Singleton
    fun okHttpFactory(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = CoroutineContextApiClientFactory(OkHttpFactory(okHttpClient))

    @Provides
    @Singleton
    fun jellyfin(
        okHttpFactory: CoroutineContextApiClientFactory,
        @ApplicationContext context: Context,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ): Jellyfin =
        createJellyfin {
            this.context = context
            this.clientInfo = clientInfo
            this.deviceInfo = deviceInfo
            apiClientFactory = okHttpFactory
            socketConnectionFactory = okHttpFactory
            minimumServerVersion = Jellyfin.minimumVersion
        }

    @Provides
    @Singleton
    fun apiClient(jellyfin: Jellyfin) = jellyfin.createApi()

    @Provides
    @Singleton
    fun rememberTabManager(
        serverRepository: ServerRepository,
        appPreference: DataStore<AppPreferences>,
        @IoCoroutineScope scope: CoroutineScope,
    ) = object : RememberTabManager {
        fun key(itemId: String) = "${serverRepository.currentServer.value?.id}_${serverRepository.currentUser.value?.id}_$itemId"

        override fun getRememberedTab(
            preferences: UserPreferences,
            itemId: String,
            defaultTab: Int,
        ): Int {
            if (preferences.appPreferences.interfacePreferences.rememberSelectedTab) {
                return preferences.appPreferences.interfacePreferences
                    .getRememberedTabsOrDefault(key(itemId), defaultTab)
            } else {
                return defaultTab
            }
        }

        override fun saveRememberedTab(
            preferences: UserPreferences,
            itemId: String,
            tabIndex: Int,
        ) {
            if (preferences.appPreferences.interfacePreferences.rememberSelectedTab) {
                scope.launch(ExceptionHandler()) {
                    appPreference.updateData {
                        it.updateInterfacePreferences {
                            putRememberedTabs(key(itemId), tabIndex)
                        }
                    }
                }
            }
        }
    }

    @Provides
    @Singleton
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @IoCoroutineScope
    fun ioCoroutineScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @DefaultCoroutineScope
    fun defaultCoroutineScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun seerrApi(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = SeerrApi(okHttpClient)
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides
    @Singleton
    fun deviceInfo(
        @ApplicationContext context: Context,
    ): DeviceInfo = androidDevice(context)
}