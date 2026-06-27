package com.github.jkrishna289.orcax.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.data.filter.DefaultForGenresFilterOptions
import com.github.jkrishna289.orcax.data.filter.DefaultForStudiosFilterOptions
import com.github.jkrishna289.orcax.data.model.SeerrItemType
import com.github.jkrishna289.orcax.preferences.PlayerBackend
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.components.ItemGrid
import com.github.jkrishna289.orcax.ui.components.LicenseInfo
import com.github.jkrishna289.orcax.ui.data.MovieSortOptions
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderGeneric
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderLiveTv
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderMovie
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderMusic
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderPhotoAlbum
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderPlaylist
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderRecordings
import com.github.jkrishna289.orcax.ui.detail.CollectionFolderTv
import com.github.jkrishna289.orcax.ui.detail.DebugPage
import com.github.jkrishna289.orcax.ui.detail.FavoritesPage
import com.github.jkrishna289.orcax.ui.detail.PersonPage
import com.github.jkrishna289.orcax.ui.detail.PlaylistDetails
import com.github.jkrishna289.orcax.ui.detail.collection.CollectionDetails
import com.github.jkrishna289.orcax.ui.detail.discover.DiscoverMovieDetails
import com.github.jkrishna289.orcax.ui.detail.discover.DiscoverPersonPage
import com.github.jkrishna289.orcax.ui.detail.discover.DiscoverSeriesDetails
import com.github.jkrishna289.orcax.ui.detail.episode.EpisodeDetails
import com.github.jkrishna289.orcax.ui.detail.movie.MovieDetails
import com.github.jkrishna289.orcax.ui.detail.music.AlbumDetailsPage
import com.github.jkrishna289.orcax.ui.detail.music.ArtistDetailsPage
import com.github.jkrishna289.orcax.ui.detail.music.NowPlayingPage
import com.github.jkrishna289.orcax.ui.detail.series.SeriesDetails
import com.github.jkrishna289.orcax.ui.detail.series.SeriesOverview
import com.github.jkrishna289.orcax.ui.discover.DiscoverPage
import com.github.jkrishna289.orcax.ui.discover.DiscoverRequestGrid
import com.github.jkrishna289.orcax.ui.main.HomePage
import com.github.jkrishna289.orcax.ui.main.SearchPage
import com.github.jkrishna289.orcax.ui.main.settings.HomeSettingsPage
import com.github.jkrishna289.orcax.ui.playback.PlayExternalPage
import com.github.jkrishna289.orcax.ui.playback.PlaybackPage
import com.github.jkrishna289.orcax.ui.preferences.PreferencesPage
import com.github.jkrishna289.orcax.ui.preferences.subtitle.SubtitleStylePage
import com.github.jkrishna289.orcax.ui.setup.InstallUpdatePage
import com.github.jkrishna289.orcax.ui.slideshow.SlideshowPage
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import timber.log.Timber

/**
 * Chose the page for the [Destination]
 */
@Composable
fun DestinationContent(
    destination: Destination,
    preferences: UserPreferences,
    onClearBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (destination.fullScreen) {
        LaunchedEffect(Unit) { onClearBackdrop.invoke() }
    }
    when (destination) {
        is Destination.Home -> {
            // Engine-driven home; degrades to the on-device home when the engine can't serve a bundle.
            com.github.jkrishna289.orcax.ui.main.EngineHomePage(
                modifier = modifier,
                onUnavailable = {
                    HomePage(
                        preferences = preferences,
                        modifier = modifier,
                    )
                },
            )
        }

        is Destination.HomeSettings -> {
            HomeSettingsPage(preferences, modifier)
        }

        is Destination.PlaybackList,
        is Destination.Playback,
        -> {
            if (preferences.appPreferences.playbackPreferences.playerBackend == PlayerBackend.EXTERNAL_PLAYER) {
                PlayExternalPage(
                    preferences = preferences,
                    destination = destination,
                    modifier = modifier,
                )
            } else {
                PlaybackPage(
                    preferences = preferences,
                    destination = destination,
                    modifier = modifier,
                )
            }
        }

        is Destination.Settings -> {
            PreferencesPage(
                preferences.appPreferences,
                destination.screen,
                modifier,
            )
        }

        is Destination.SubtitleSettings -> {
            SubtitleStylePage(
                preferences.appPreferences,
                destination.hdr,
                modifier,
            )
        }

        is Destination.SeriesOverview -> {
            SeriesOverview(
                preferences = preferences,
                destination = destination,
                initialSeasonEpisode = destination.seasonEpisode,
                modifier = modifier,
            )
        }

        is Destination.MediaItem -> {
            when (destination.type) {
                BaseItemKind.SERIES -> {
                    SeriesDetails(
                        preferences,
                        destination,
                        modifier,
                    )
                }

                BaseItemKind.MOVIE -> {
                    MovieDetails(
                        preferences,
                        destination,
                        modifier,
                    )
                }

                BaseItemKind.VIDEO,
                BaseItemKind.MUSIC_VIDEO,
                -> {
                    // TODO Use VideoDetails
                    MovieDetails(
                        preferences,
                        destination,
                        modifier,
                    )
                }

                BaseItemKind.EPISODE -> {
                    EpisodeDetails(
                        preferences,
                        destination,
                        modifier,
                    )
                }

                BaseItemKind.BOX_SET -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    CollectionDetails(
                        preferences = preferences,
                        itemId = destination.itemId,
                        modifier = modifier,
                    )
                }

                BaseItemKind.PLAYLIST -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    PlaylistDetails(
                        preferences = preferences,
                        destination = destination,
                        modifier = modifier,
                    )
                }

                BaseItemKind.COLLECTION_FOLDER -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    CollectionFolder(
                        preferences = preferences,
                        destination = destination,
                        collectionType = destination.collectionType,
                        usePostersOverride = null,
                        recursiveOverride = null,
                        modifier = modifier,
                    )
                }

                BaseItemKind.FOLDER -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    CollectionFolder(
                        preferences = preferences,
                        destination = destination,
                        collectionType = destination.collectionType,
                        usePostersOverride = true,
                        recursiveOverride = null,
                        modifier = modifier,
                    )
                }

                BaseItemKind.USER_VIEW -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    CollectionFolder(
                        preferences = preferences,
                        destination = destination,
                        collectionType = destination.collectionType,
                        usePostersOverride = null,
                        recursiveOverride = true,
                        modifier = modifier,
                    )
                }

                BaseItemKind.PERSON -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    PersonPage(
                        preferences,
                        destination,
                        modifier,
                    )
                }

                BaseItemKind.PHOTO_ALBUM -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    CollectionFolderPhotoAlbum(
                        preferences = preferences,
                        itemId = destination.itemId,
                        recursive = false,
                        modifier = modifier,
                    )
                }

                BaseItemKind.MUSIC_ALBUM -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    AlbumDetailsPage(
                        preferences = preferences,
                        itemId = destination.itemId,
                        modifier = modifier,
                    )
                }

                BaseItemKind.MUSIC_ARTIST -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    ArtistDetailsPage(
                        preferences = preferences,
                        itemId = destination.itemId,
                        modifier = modifier,
                    )
                }

                else -> {
                    Timber.w("Unsupported item type: ${destination.type}")
                    Text("Unsupported item type: ${destination.type}", modifier)
                }
            }
        }

        is Destination.FilteredCollection -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            CollectionFolderGeneric(
                preferences = preferences,
                itemId = destination.itemId,
                filter = destination.filter,
                recursive = destination.recursive,
                usePosters = true,
                playEnabled = true, // TODO only genres use this currently, so might need to change in future
                filterOptions =
                    when (destination.parentType) {
                        BaseItemKind.GENRE -> DefaultForGenresFilterOptions
                        BaseItemKind.STUDIO -> DefaultForStudiosFilterOptions
                        else -> throw IllegalArgumentException("Unsupported parentType ${destination.parentType}")
                    },
                modifier = modifier,
            )
        }

        is Destination.Recordings -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            CollectionFolderRecordings(
                preferences,
                destination.itemId,
                false,
                modifier,
            )
        }

        is Destination.ItemGrid -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            ItemGrid(
                destination,
                modifier,
            )
        }

        is Destination.Slideshow -> {
            SlideshowPage(
                slideshow = destination,
            )
        }

        Destination.Favorites -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            FavoritesPage(
                preferences = preferences,
                modifier = modifier,
            )
        }

        Destination.Categories -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            com.github.jkrishna289.orcax.ui.main.CategoriesPage(
                modifier = modifier,
            )
        }

        Destination.NowPlaying -> {
            NowPlayingPage(modifier)
        }

        Destination.UpdateApp -> {
            InstallUpdatePage(preferences, modifier)
        }

        Destination.License -> {
            LicenseInfo(modifier)
        }

        Destination.Search -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            SearchPage(
                userPreferences = preferences,
                modifier = modifier,
            )
        }

        Destination.Debug -> {
            DebugPage(preferences, modifier)
        }

        Destination.EngineCards -> {
            com.github.jkrishna289.orcax.ui.engine.EngineCardPreviewScreen(modifier = modifier)
        }

        Destination.Discover -> {
            DiscoverPage(
                preferences = preferences,
                modifier = modifier,
            )
        }

        is Destination.DiscoveredItem -> {
            when (destination.item.type) {
                SeerrItemType.MOVIE -> {
                    DiscoverMovieDetails(
                        preferences = preferences,
                        destination = destination,
                        modifier = modifier,
                    )
                }

                SeerrItemType.TV -> {
                    DiscoverSeriesDetails(
                        preferences = preferences,
                        destination = destination,
                        modifier = modifier,
                    )
                }

                SeerrItemType.PERSON -> {
                    LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                    DiscoverPersonPage(
                        person = destination.item,
                        modifier = modifier,
                    )
                }

                SeerrItemType.UNKNOWN -> {
                    Text(
                        text = "Unknown discover type",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        is Destination.DiscoverMoreResult -> {
            LaunchedEffect(Unit) { onClearBackdrop.invoke() }
            DiscoverRequestGrid(
                destination = destination,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun CollectionFolder(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    collectionType: CollectionType?,
    usePostersOverride: Boolean?,
    recursiveOverride: Boolean?,
    modifier: Modifier = Modifier,
) {
    when (collectionType) {
        CollectionType.TVSHOWS -> {
            CollectionFolderTv(
                preferences,
                destination,
                modifier,
            )
        }

        CollectionType.MOVIES -> {
            CollectionFolderMovie(
                preferences,
                destination,
                modifier,
            )
        }

        CollectionType.BOXSETS -> {
            CollectionFolderGeneric(
                preferences = preferences,
                itemId = destination.itemId,
                usePosters = true,
                recursive = false,
                playEnabled = false,
                modifier = modifier,
                sortOptions = MovieSortOptions,
            )
        }

        CollectionType.PLAYLISTS -> {
            CollectionFolderPlaylist(
                preferences,
                destination.itemId,
                true,
                modifier,
            )
        }

        CollectionType.LIVETV -> {
            CollectionFolderLiveTv(
                preferences = preferences,
                destination = destination,
                modifier = modifier,
            )
        }

        CollectionType.MUSIC -> {
            CollectionFolderMusic(
                preferences,
                destination,
                modifier,
            )
        }

        CollectionType.HOMEVIDEOS,
        CollectionType.PHOTOS,
        -> {
            CollectionFolderPhotoAlbum(
                preferences = preferences,
                itemId = destination.itemId,
                recursive = recursiveOverride ?: false,
                modifier = modifier,
            )
        }

        CollectionType.MUSICVIDEOS,
        CollectionType.BOOKS,
        -> {
            CollectionFolderGeneric(
                preferences,
                destination.itemId,
                usePosters = usePostersOverride ?: false,
                recursive = recursiveOverride ?: false,
                playEnabled = true,
                modifier = modifier,
            )
        }

        CollectionType.FOLDERS,
        CollectionType.TRAILERS,
        CollectionType.UNKNOWN,
        null,
        -> {
            CollectionFolderGeneric(
                preferences,
                destination.itemId,
                usePosters = usePostersOverride ?: false,
                recursive = recursiveOverride ?: false,
                playEnabled = false,
                modifier = modifier,
            )
        }
    }
}
