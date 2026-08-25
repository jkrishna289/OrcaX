package com.github.jkrishna289.orcax.ui.detail.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.api.seerr.model.MovieDetails
import com.github.jkrishna289.orcax.data.model.DiscoverRating
import com.github.jkrishna289.orcax.data.model.SeerrAvailability
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.components.GenreText
import com.github.jkrishna289.orcax.ui.components.OverviewText
import com.github.jkrishna289.orcax.ui.components.QuickDetails
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.letNotEmpty
import com.github.jkrishna289.orcax.ui.listToDotString
import com.github.jkrishna289.orcax.ui.roundMinutes
import com.github.jkrishna289.orcax.util.ExceptionHandler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

@Composable
fun DiscoverMovieDetailsHeader(
    preferences: UserPreferences,
    movie: MovieDetails,
    rating: DiscoverRating?,
    bringIntoViewRequester: BringIntoViewRequester,
    overviewOnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        LibraryStatusChip(movie)

        // Title
        Text(
            text = movie.title ?: "",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(.75f),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f),
        ) {
            val padding = 4.dp
            val details =
                remember(movie, rating) {
                    buildList {
                        movie.releaseDate?.let(::add)
                        movie.runtime
                            ?.toDouble()
                            ?.minutes
                            ?.roundMinutes
                            ?.toString()
                            ?.let(::add)
                        val release =
                            movie.releases
                                ?.results
                                ?.firstOrNull { it.iso31661 == Locale.getDefault().country }
                                ?: movie.releases
                                    ?.results
                                    ?.firstOrNull { it.iso31661 == Locale.US.country }
                                ?: movie.releases
                                    ?.results
                                    ?.firstOrNull()

                        release
                            ?.releaseDates
                            ?.firstOrNull()
                            ?.certification
                            ?.takeIf { it.isNotNullOrBlank() }
                            ?.let(::add)
                    }.let {
                        listToDotString(
                            it,
                            rating?.audienceRating,
                            rating?.criticRating?.toFloat(),
                        )
                    }
                }

            QuickDetails(details, null)
            movie.genres?.mapNotNull { it.name }?.letNotEmpty {
                GenreText(it, Modifier.padding(bottom = padding))
            }

            val tagline = remember { movie.tagline?.takeIf { it.isNotNullOrBlank() } }
            tagline?.let {
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier,
                )
            }

            // Description
            movie.overview?.let { overview ->
                OverviewText(
                    overview = overview,
                    maxLines = 3,
                    onClick = overviewOnClick,
                    textBoxHeight = Dp.Unspecified,
                    modifier =
                        Modifier.onFocusChanged {
                            if (it.isFocused) {
                                scope.launch(ExceptionHandler()) {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                )
            }

            val directorName =
                remember(movie.credits?.crew) {
                    movie.credits
                        ?.crew
                        ?.filter { it.job == "Director" && it.name.isNotNullOrBlank() }
                        ?.joinToString(", ") { it.name!! }
                        ?.takeIf { it.isNotNullOrBlank() }
                }

            directorName
                ?.let {
                    Text(
                        text = stringResource(R.string.directed_by, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
        }
    }
}

/**
 * The one fact that decides this whole screen, stated before the title.
 *
 * The wording is deliberately a state of *your library*, never a state of the world: "Not in your
 * library", not "Unavailable". The film exists; you just don't own it yet. "Coming Soon" is reserved
 * for titles that genuinely haven't been released, where requesting is the only thing that can be
 * offered, and an owned title says so in teal.
 */
@Composable
private fun LibraryStatusChip(movie: MovieDetails) {
    val availability = SeerrAvailability.from(movie.mediaInfo?.status) ?: SeerrAvailability.UNKNOWN
    val unreleased =
        remember(movie.releaseDate) {
            movie.releaseDate
                ?.takeIf { it.length >= 10 }
                ?.runCatching { LocalDate.parse(substring(0, 10)) }
                ?.getOrNull()
                ?.isAfter(LocalDate.now()) == true
        }

    val (labelRes, color) =
        when {
            availability == SeerrAvailability.AVAILABLE ->
                R.string.in_your_library to InLibraryTeal

            unreleased -> R.string.coming_soon to ComingSoonPeriwinkle

            availability == SeerrAvailability.UNKNOWN ->
                R.string.not_in_your_library to AppColors.GoldenYellow

            // Requested / processing already say so on the action button; a second chip repeating it
            // would be noise.
            else -> return
        }

    val shape = RoundedCornerShape(3.dp)
    Text(
        text = stringResource(labelRes).uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier =
            Modifier
                .background(color.copy(alpha = 0.14f), shape)
                .border(1.dp, color.copy(alpha = 0.6f), shape)
                .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Teal for an owned title, matching the Requested pill in RequestAvailabilityBadge. */
private val InLibraryTeal = Color(0xFF2DE0C0)

/** Periwinkle for a title that isn't out yet — neither actionable-gold nor settled-teal. */
private val ComingSoonPeriwinkle = Color(0xFF8B8BEC)
