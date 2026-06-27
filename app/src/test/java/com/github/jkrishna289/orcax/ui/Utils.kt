package com.github.jkrishna289.orcax.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.jellyfin.sdk.api.client.Response

@OptIn(ExperimentalTestApi::class)
fun SemanticsNodeInteraction.performClickEnter() =
    performKeyInput {
        pressKey(Key.DirectionCenter)
    }

fun <T> successResponse(content: T) = Response(content, 200, emptyMap())
