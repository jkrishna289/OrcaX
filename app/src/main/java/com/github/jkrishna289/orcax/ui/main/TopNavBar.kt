package com.github.jkrishna289.orcax.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.asFlow
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.JellyfinServer
import com.github.jkrishna289.orcax.data.model.JellyfinUser
import com.github.jkrishna289.orcax.services.MusicService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.SetupDestination
import com.github.jkrishna289.orcax.services.SetupNavigationManager
import com.github.jkrishna289.orcax.ui.components.RestoreFocusOnDispose
import com.github.jkrishna289.orcax.ui.components.TextButton
import com.github.jkrishna289.orcax.ui.components.focusTrap
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.preferences.PreferenceScreenOption
import com.github.jkrishna289.orcax.ui.setup.UserIconCardImage
import com.github.jkrishna289.orcax.ui.util.LocalClock
import dagger.hilt.android.lifecycle.HiltViewModel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import javax.inject.Inject

/** Real GPU blur is only available from Android 12 (API 31); below that the scrim stands alone. */
private val SUPPORTS_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Approximate rendered height of the fixed top nav (top inset + icon row + bottom padding). Used as
 * the bring-into-view offset so focused cards in scrolled rows land BELOW the bar, not under it (#8).
 */
val TopNavBarHeight = 96.dp

@HiltViewModel
class TopNavViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
        private val setupNavigationManager: SetupNavigationManager,
        private val musicService: MusicService,
    ) : ViewModel() {
        val currentUser = serverRepository.currentUser.asFlow()
        val currentServer = serverRepository.currentServer.asFlow()

        fun getUserImage(user: JellyfinUser): String = api.imageApi.getUserImageUrl(user.id)

        fun openSearch() = navigationManager.navigateTo(Destination.Search)

        fun openCategories() = navigationManager.navigateTo(Destination.Categories)

        fun openSettings() = navigationManager.navigateTo(Destination.Settings(PreferenceScreenOption.BASIC))

        fun switchProfile(server: JellyfinServer) {
            viewModelScope.launchDefault {
                musicService.stop()
                setupNavigationManager.navigateTo(SetupDestination.UserList(server))
            }
        }
    }

/**
 * The fixed top navigation bar that replaces the old left rail. Monochromatic icons only
 * (Search + Categories on the left; profile avatar + clock on the right). The background
 * is transparent over the billboard ([isAtTop]) and frosts once the user scrolls, so the icons
 * stay legible. Avatar opens a small Settings / Switch-Profile menu, keeping the user on Home.
 */
@Composable
fun TopNavBar(
    isAtTop: Boolean,
    contentFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    navFocusRequester: FocusRequester? = null,
    viewModel: TopNavViewModel = hiltViewModel(),
) {
    val user by viewModel.currentUser.collectAsStateWithLifecycle(initialValue = null)
    val server by viewModel.currentServer.collectAsStateWithLifecycle(initialValue = null)
    val timeString by LocalClock.current.timeString
    var menuOpen by remember { mutableStateOf(false) }
    // So the avatar regains focus when its menu closes (instead of focus being dropped).
    val avatarFocus = remember { FocusRequester() }

    // Rigid, linear frost transition (no spring/bounce, per the design language).
    val scrimColor by animateColorAsState(
        targetValue = if (isAtTop) Color.Transparent else Color.Black.copy(alpha = 0.6f),
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "topnav-scrim",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        // Frosted background layer (only this layer blurs — the icons stay crisp). Real blur on
        // API 31+, plain translucent gradient elsewhere / on the emulator.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .then(if (SUPPORTS_BLUR && !isAtTop) Modifier.blur(24.dp) else Modifier)
                    .background(
                        Brush.verticalGradient(
                            0f to scrimColor,
                            1f to Color.Transparent,
                        ),
                    ),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    // TV safe-area: keep chrome clear of the panel's overscan edges.
                    .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 16.dp)
                    // D-pad Down from anywhere in the bar enters the content (billboard Play).
                    .focusGroup()
                    .focusProperties { down = contentFocusRequester },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.focusGroup(),
            ) {
                NavIcon(
                    icon = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search),
                    onClick = viewModel::openSearch,
                    downTarget = contentFocusRequester,
                    focusRequester = navFocusRequester,
                )
                NavIcon(
                    icon = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.categories),
                    onClick = viewModel::openCategories,
                    downTarget = contentFocusRequester,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = timeString,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(end = 16.dp),
            )

            val u = user
            if (u != null) {
                val avatarInteraction = remember { MutableInteractionSource() }
                val avatarFocused by avatarInteraction.collectIsFocusedAsState()
                val imageUrl = remember(u) { viewModel.getUserImage(u) }
                Surface(
                    onClick = { menuOpen = true },
                    modifier =
                        Modifier
                            .focusRequester(avatarFocus)
                            .focusProperties { down = contentFocusRequester },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.15f),
                            pressedContainerColor = Color.White.copy(alpha = 0.15f),
                        ),
                    interactionSource = avatarInteraction,
                ) {
                    UserIconCardImage(
                        id = u.id,
                        name = u.name,
                        imageUrl = imageUrl,
                        alpha = if (avatarFocused) 1f else 0.85f,
                        modifier = Modifier.size(40.dp).padding(2.dp),
                    )
                }
            }
        }

        AvatarMenu(
            visible = menuOpen,
            restoreFocusTo = avatarFocus,
            onDismiss = { menuOpen = false },
            onSettings = {
                menuOpen = false
                viewModel.openSettings()
            },
            onSwitchProfile = {
                menuOpen = false
                server?.let { viewModel.switchProfile(it) }
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** A single monochromatic, focusable top-nav icon: white with alpha, brighter when focused. */
@Composable
private fun NavIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downTarget: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        // Per-control Down target: directional focus reads the focused node's own properties, so
        // wiring it here (not just on the parent) makes D-pad Down reliably reach the content (#1).
        // The optional focusRequester lets the billboard point Up here so the bar is reachable.
        modifier =
            modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.22f),
                pressedContainerColor = Color.White.copy(alpha = 0.22f),
            ),
        // Bold, unmistakable focus ring — essential on a 10-ft TV UI.
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder =
                    Border(
                        border = BorderStroke(2.dp, Color.White),
                        shape = CircleShape,
                    ),
            ),
        interactionSource = interactionSource,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (focused) 1f else 0.6f),
            modifier = Modifier.padding(8.dp).size(28.dp),
        )
    }
}

/**
 * Small contextual menu anchored under the avatar with Settings + Switch Profile. Focus is trapped
 * while open (D-pad cannot wander into the page behind it) and returns to [restoreFocusTo] on close.
 */
@Composable
private fun AvatarMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusTo: FocusRequester? = null,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        BackHandler(enabled = true, onBack = onDismiss)
        RestoreFocusOnDispose(restoreFocusTo)
        val firstItem = remember { FocusRequester() }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .padding(top = 72.dp, end = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(8.dp)
                    .width(180.dp)
                    .focusTrap(),
        ) {
            TextButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth().focusRequester(firstItem),
            ) {
                Text(stringResource(R.string.settings))
            }
            TextButton(
                onClick = onSwitchProfile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.switch_profile))
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { firstItem.requestFocus() } }
    }
}
