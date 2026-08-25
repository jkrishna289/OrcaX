package com.github.jkrishna289.orcax.ui.detail.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.services.torrent.StreamWatch
import com.github.jkrishna289.orcax.ui.playback.toTimeString
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import kotlinx.coroutines.delay

/** Long enough that a stop-or-skip press on the way out of the film cannot land on "Keep". */
private const val KEEP_FOCUS_DELAY_MS = 400L

/** No input for this long and the prompt lets itself out. Silence is never read as consent. */
private const val KEEP_TIMEOUT_MS = 20_000L

/**
 * Screen 08 — asked once, when you stop.
 *
 * Watching a stream writes a timestamp from the first minute exactly as a library title does, so the
 * film is already in Continue Watching by the time this appears. That changes what is being asked:
 * not "save this", but "make the thing you are already part-way through permanent".
 *
 * Deliberately not a download. No progress bar, no storage figure, no cancel-in-flight UI — the
 * wording is about the *library*, because underneath it is the same request as the one on the details
 * screen, and no new mental model should arrive at the tail end of a film.
 */
@Composable
fun KeepInLibraryPrompt(
    watch: StreamWatch,
    runtimeMinutes: Int?,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keepFocus = remember { FocusRequester() }
    LaunchedEffect(watch) {
        delay(KEEP_FOCUS_DELAY_MS)
        keepFocus.tryRequestFocus()
        delay(KEEP_TIMEOUT_MS - KEEP_FOCUS_DELAY_MS)
        onDismiss()
    }

    FullScreenStage(onDismiss = onDismiss) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0810)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(13.dp),
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 58.dp)
                        .fillMaxWidth(0.62f),
            ) {
                Text(
                    text = stringResource(R.string.keep_stopped_at, watch.positionMs.toTimeString()),
                    color = OnStage.copy(alpha = 0.42f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = stringResource(R.string.keep_title, watch.title),
                    color = OnStageBright,
                    fontSize = 39.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.keep_body),
                    color = OnStage.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    StageButton(
                        text = stringResource(R.string.keep_confirm),
                        onClick = onKeep,
                        primary = true,
                        modifier = Modifier.focusRequester(keepFocus),
                    )
                    StageButton(text = stringResource(R.string.keep_decline), onClick = onDismiss)
                }

                Text(
                    text = stringResource(R.string.keep_footnote),
                    color = OnStage.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            ContinueWatchingCard(
                watch = watch,
                runtimeMinutes = runtimeMinutes,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 58.dp),
            )
        }
    }
}

/**
 * The proof that the position is already saved. It is not decoration: the prompt claims the film is
 * in Continue Watching, and this is the claim made visible so the viewer doesn't have to take it on
 * faith before deciding.
 */
@Composable
private fun ContinueWatchingCard(
    watch: StreamWatch,
    runtimeMinutes: Int?,
    modifier: Modifier = Modifier,
) {
    val runtimeMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
    val progress = runtimeMs?.let { (watch.positionMs.toFloat() / it).coerceIn(0f, 1f) }

    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier.width(200.dp),
    ) {
        Text(
            text = stringResource(R.string.continue_watching),
            color = OnStage.copy(alpha = 0.42f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF3A3350), Color(0xFF1C1828))),
                        RoundedCornerShape(7.dp),
                    ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
            ) {
                Text(
                    text = watch.title,
                    color = OnStageBright.copy(alpha = 0.94f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (progress != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFFA99CFF), Color(0xFF4FC8BC)),
                                        ),
                                        RoundedCornerShape(50),
                                    ),
                        )
                    }
                }
            }
        }
        if (runtimeMs != null) {
            Text(
                text =
                    stringResource(
                        R.string.keep_time_left,
                        ((runtimeMs - watch.positionMs).coerceAtLeast(0) / 60_000L).toInt(),
                    ),
                color = OnStage.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
    }
}
