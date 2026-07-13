package com.github.jkrishna289.orcax.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.github.jkrishna289.orcax.BuildConfig
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.AppDatabase
import com.github.jkrishna289.orcax.data.ItemPlaybackDao
import com.github.jkrishna289.orcax.data.JellyfinServerDao
import com.github.jkrishna289.orcax.data.LibraryDisplayInfoDao
import com.github.jkrishna289.orcax.data.Migrations
import com.github.jkrishna289.orcax.data.PlaybackEffectDao
import com.github.jkrishna289.orcax.data.PlaybackLanguageChoiceDao
import com.github.jkrishna289.orcax.data.QualityPreferenceDao
import com.github.jkrishna289.orcax.data.SeerrServerDao
import com.github.jkrishna289.orcax.data.ServerPreferencesDao
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.preferences.AppPreferencesSerializer
import com.github.jkrishna289.orcax.services.SeerrApi
import com.github.jkrishna289.orcax.services.hilt.AppModule
import com.github.jkrishna289.orcax.services.hilt.AuthOkHttpClient
import com.github.jkrishna289.orcax.services.hilt.DatabaseModule
import com.github.jkrishna289.orcax.services.hilt.DefaultCoroutineScope
import com.github.jkrishna289.orcax.services.hilt.DefaultDispatcher
import com.github.jkrishna289.orcax.services.hilt.DeviceModule
import com.github.jkrishna289.orcax.services.hilt.IoCoroutineScope
import com.github.jkrishna289.orcax.services.hilt.IoDispatcher
import com.github.jkrishna289.orcax.services.hilt.StandardOkHttpClient
import com.github.jkrishna289.orcax.util.CoroutineContextApiClientFactory
import com.github.jkrishna289.orcax.util.RememberTabManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DeviceModule::class, AppModule::class],
)
object TestModule {
    val testDispatcher = StandardTestDispatcher()

    @Provides
    @Singleton
    fun deviceInfo(
        @ApplicationContext context: Context,
    ): DeviceInfo = DeviceInfo("test_device_id", "test_device")

    @Provides
    @Singleton
    fun clientInfo(
        @ApplicationContext context: Context,
    ): ClientInfo =
        ClientInfo(
            name = context.getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
        )

    @StandardOkHttpClient
    @Provides
    @Singleton
    fun okHttpClient() =
        OkHttpClient
            .Builder()
            .apply {
                // TODO user agent, timeouts, logging, etc
            }.build()

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
    ): Jellyfin {
        val jellyfin: Jellyfin = mockk()
        every { jellyfin.clientInfo } returns clientInfo
        every { jellyfin.deviceInfo } returns deviceInfo
        every { jellyfin.options } returns
            JellyfinOptions(
                context,
                clientInfo,
                deviceInfo,
                okHttpFactory,
                okHttpFactory,
                Jellyfin.minimumVersion,
            )
        every { jellyfin.createApi(any()) } returns apiClient(jellyfin)
        every { jellyfin.createApi(any(), any(), any(), any(), any()) } returns apiClient(jellyfin)
        return jellyfin
    }

    @Provides
    @Singleton
    fun apiClient(jellyfin: Jellyfin): ApiClient {
        val api: ApiClient = mockk()
        every { api.clientInfo } returns jellyfin.clientInfo!!
        every { api.deviceInfo } returns jellyfin.deviceInfo!!
        every { api.update(any(), any(), any(), any()) } returns Unit
        return api
    }

    /**
     * Implementation of [RememberTabManager] which remembers by server, user, & item
     */
    @Provides
    @Singleton
    fun rememberTabManager(
        serverRepository: ServerRepository,
        appPreference: DataStore<AppPreferences>,
        @IoCoroutineScope scope: CoroutineScope,
    ): RememberTabManager = mockk()

    @Provides
    @Singleton
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = testDispatcher

    @Provides
    @Singleton
    @IoCoroutineScope
    fun ioCoroutineScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = testDispatcher

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
    ): WorkManager = mockk()

    @Provides
    @Singleton
    fun seerrApi(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ): SeerrApi = mockk()
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {
    @Module
    @InstallIn(SingletonComponent::class)
    object DatabaseModule {
        @Provides
        @Singleton
        fun database(
            @ApplicationContext context: Context,
        ): AppDatabase =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    AppDatabase::class.java,
                ).addMigrations(Migrations.Migrate2to3)
                .allowMainThreadQueries()
                .setQueryCallback({ sqlQuery, args ->
                    Timber.v("sqlQuery=$sqlQuery, args=$args")
                }, Dispatchers.IO.asExecutor())
                .build()

        @Provides
        @Singleton
        fun serverDao(db: AppDatabase): JellyfinServerDao = db.serverDao()

        @Provides
        @Singleton
        fun itemPlaybackDao(db: AppDatabase): ItemPlaybackDao = db.itemPlaybackDao()

        @Provides
        @Singleton
        fun serverPreferencesDao(db: AppDatabase): ServerPreferencesDao = db.serverPreferencesDao()

        @Provides
        @Singleton
        fun libraryDisplayInfoDao(db: AppDatabase): LibraryDisplayInfoDao = db.libraryDisplayInfoDao()

        @Provides
        @Singleton
        fun playbackLanguageChoiceDao(db: AppDatabase): PlaybackLanguageChoiceDao = db.playbackLanguageChoiceDao()

        @Provides
        @Singleton
        fun seerrServerDao(db: AppDatabase): SeerrServerDao = db.seerrServerDao()

        @Provides
        @Singleton
        fun playbackEffectDao(db: AppDatabase): PlaybackEffectDao = db.playbackEffectDao()

        @Provides
        @Singleton
        fun qualityPreferenceDao(db: AppDatabase): QualityPreferenceDao = db.qualityPreferenceDao()

        @Provides
        @Singleton
        fun userPreferencesDataStore(
            @ApplicationContext context: Context,
            userPreferencesSerializer: AppPreferencesSerializer,
        ): DataStore<AppPreferences> =
            DataStoreFactory.create(
                serializer = userPreferencesSerializer,
                produceFile = { context.dataStoreFile("app_preferences.pb") },
                corruptionHandler =
                    ReplaceFileCorruptionHandler(
                        produceNewData = { AppPreferences.getDefaultInstance() },
                    ),
            )

        @Provides
        @Singleton
        fun keyValueDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile("key_value") },
            )
    }
}
