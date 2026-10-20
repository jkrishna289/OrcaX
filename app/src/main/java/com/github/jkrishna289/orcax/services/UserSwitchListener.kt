package com.github.jkrishna289.orcax.services

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.ui.launchIO
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens for JF user switching in the app to load that user's home settings
 */
@ActivityScoped
class UserSwitchListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val homeSettingsService: HomeSettingsService,
    ) {
        init {
            context as AppCompatActivity
            context.lifecycleScope.launchIO {
                serverRepository.currentUser.asFlow().collect { user ->
                    Timber.d("New user")
                    homeSettingsService.currentSettings.update { HomePageResolvedSettings.EMPTY }
                    if (user != null) {
                        homeSettingsService.loadCurrentSettings(user.id)
                    }
                }
            }
        }
    }
