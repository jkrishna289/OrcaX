package com.github.jkrishna289.orcax.preferences

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

val PlaybackPreferences.skipBackOnResume: Duration?
    get() =
        if (skipBackOnResumeSeconds > 0) {
            skipBackOnResumeSeconds.milliseconds
        } else {
            null
        }
