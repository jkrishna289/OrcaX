package com.github.jkrishna289.orcax.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color

/**
 * Fences D-pad focus inside this subtree: directional presses can never move focus out to the
 * (dimmed) content behind an open overlay. `focusGroup` alone only *groups* — it does not fence.
 * Use via [FocusTrapOverlay] for scrimmed modals, or apply directly to an anchored menu.
 */
fun Modifier.focusTrap(): Modifier =
    this
        .focusGroup()
        .focusProperties { onExit = { cancelFocusChange() } }

/**
 * Restores focus to [target] when this composable leaves composition — the standard hand-back for
 * dismissed overlays, so the invoking control regains focus instead of focus being dropped.
 */
@Composable
fun RestoreFocusOnDispose(target: FocusRequester?) {
    DisposableEffect(target) {
        onDispose { target?.let { runCatching { it.requestFocus() } } }
    }
}

/**
 * A modal, focus-trapping overlay scaffold for TV: scrimmed, BACK dismisses, D-pad focus cannot
 * escape to the content behind it, and focus is handed back to [restoreFocusTo] on dismissal.
 * The caller still owns initial focus (request it in a `LaunchedEffect` on the first control).
 */
@Composable
fun FocusTrapOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusTo: FocusRequester? = null,
    scrimColor: Color = Color.Black.copy(alpha = 0.7f),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    RestoreFocusOnDispose(restoreFocusTo)
    Box(
        contentAlignment = contentAlignment,
        modifier =
            modifier
                .fillMaxSize()
                .background(scrimColor)
                .focusTrap(),
        content = content,
    )
}
