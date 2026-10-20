package app.mawja.android.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.mawja.android.data.local.PlayerPreferences
import app.mawja.android.data.local.PlaylistRepository
import app.mawja.android.data.local.SubscriptionRepository
import app.mawja.android.data.music.model.ArtistDetails
import app.mawja.android.data.music.model.MusicPlaylist
import app.mawja.android.data.music.model.MusicTrack
import app.mawja.android.data.playlist.sortedFor
import app.mawja.android.data.scrobble.LastFmDiscovery
import app.mawja.android.data.scrobble.ScrobbleService
import app.mawja.android.data.scrobble.ScrobbleStore
import app.mawja.android.ui.components.music.section.MusicHomeShelf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHELF_LIMIT = 20

/**
 * The viewer's own corner of the music home: their music playlists in the playlists page's order,
 * the music channels they subscribe to, newest first, the sections they chose to hide, and, for
 * Last.fm accounts, the discovery shelf. Only that shelf touches the network, and only once shown.
 */
@HiltViewModel
class MusicHomeLibraryViewModel
    @Inject
    constructor(
        playlistRepository: PlaylistRepository,
        subscriptionRepository: SubscriptionRepository,
        preferences: PlayerPreferences,
        scrobbleStore: ScrobbleStore,
        private val lastFmDiscovery: LastFmDiscovery,
    ) : ViewModel() {
        private val sharing = SharingStarted.WhileSubscribed(5_000)

        val playlists: StateFlow<List<MusicPlaylist>> =
            combine(playlistRepository.getMusicPlaylistsFlow(), preferences.playlistListOrder) { playlists, order ->
                playlists.sortedFor(order).take(SHELF_LIMIT).map { playlist ->
                    MusicPlaylist(
                        id = playlist.id,
                        title = playlist.name,
                        thumbnailUrl = playlist.thumbnailUrl,
                        trackCount = playlist.videoCount,
                    )
                }
            }.stateIn(viewModelScope, sharing, emptyList())

        val subscriptions: StateFlow<List<ArtistDetails>> =
            subscriptionRepository
                .getAllSubscriptions()
                .map { subscriptions ->
                    subscriptions
                        .filter { it.isMusic }
                        .sortedByDescending { it.subscribedAt }
                        .map {
                            ArtistDetails(
                                name = it.channelName,
                                channelId = it.channelId,
                                thumbnailUrl = it.channelThumbnail,
                                subscriberCount = 0L,
                            )
                        }
                }.stateIn(viewModelScope, sharing, emptyList())

        val lastFmSignedIn: StateFlow<Boolean> =
            scrobbleStore.settings
                .map { ScrobbleService.LASTFM in it.accounts }
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, false)

        private val _discovery = MutableStateFlow<List<MusicTrack>>(emptyList())
        val discovery: StateFlow<List<MusicTrack>> = _discovery.asStateFlow()
        private var discoveryJob: Job? = null

        /** Called when the shelf comes into view; the discovery service keeps its result while the seeds hold. */
        fun loadDiscovery() {
            if (discoveryJob?.isActive == true) return
            discoveryJob =
                viewModelScope.launch { _discovery.value = runCatching { lastFmDiscovery.tracks() }.getOrDefault(_discovery.value) }
        }

        val hiddenShelves: StateFlow<Set<MusicHomeShelf>> =
            preferences.hiddenMusicHomeShelves
                .map(MusicHomeShelf::fromStored)
                .stateIn(viewModelScope, sharing, emptySet())
    }
