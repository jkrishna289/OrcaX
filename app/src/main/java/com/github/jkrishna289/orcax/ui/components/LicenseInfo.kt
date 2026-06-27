package com.github.jkrishna289.orcax.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * Displays the app's attribution credit plus dependencies' license information to comply with attribution
 */
@Composable
fun LicenseInfo(modifier: Modifier = Modifier) {
    val libraries by produceLibraries()

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.about_attribution),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LibrariesContainer(libraries, modifier = Modifier.weight(1f))
    }
}
