package com.github.jkrishna289.orcax.ui.detail.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.jkrishna289.orcax.ui.PreviewTvSpec
import com.github.jkrishna289.orcax.ui.theme.OrcaTheme

// .align(Alignment.BottomEnd)
@Composable
fun RecordingMarker(
    isRecording: Boolean,
    isSeriesRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (isSeriesRecording) {
            val color = if (isRecording) Color.Red else Color.Gray
            Box(
                modifier =
                    Modifier
                        .padding(4.dp)
                        .size(16.dp)
                        .background(color, shape = CircleShape),
            )
            Box(
                modifier =
                    Modifier
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 10.dp)
                        .offset(6.dp)
                        .size(16.dp)
                        .background(color.copy(alpha = .5f), shape = CircleShape),
            )
        } else if (isRecording) {
            Box(
                modifier =
                    Modifier
                        .padding(4.dp)
                        .size(16.dp)
                        .background(Color.Red, shape = CircleShape),
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun RecordingMarkerPreview() {
    OrcaTheme {
        Column {
            RecordingMarker(true, false)
            RecordingMarker(true, true)
            RecordingMarker(false, true)
        }
    }
}
