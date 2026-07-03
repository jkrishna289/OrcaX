package com.github.jkrishna289.orcax.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R

/**
 * The Back-on-Home exit confirmation: a small, focus-trapped modal with Cancel focused by default
 * so a stray extra Back press can't quit the app. BACK inside the dialog cancels.
 */
@Composable
fun ExitConfirmDialog(
    onExit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    FocusTrapOverlay(onDismiss = onDismiss, modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.exit_confirm_title, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.exit_confirm_button))
                }
            }
        }
    }
}
