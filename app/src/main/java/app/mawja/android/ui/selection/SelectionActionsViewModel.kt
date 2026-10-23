/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mawja.android.data.local.PlaylistRepository
import app.mawja.android.data.model.PlaylistInfo
import app.mawja.android.data.model.Video
import app.mawja.android.data.music.model.toMusicTrack
import app.mawja.android.data.video.BackgroundDownloadQueuer
import app.mawja.android.player.EnhancedMusicPlayerManager
import app.mawja.android.player.EnhancedPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Provider

/** The bulk actions a multi-select toolbar offers (#84): play next, download, add to a playlist. */
@HiltViewModel
class SelectionActionsViewModel
    @Inject
    constructor(
        private val playlists: PlaylistRepository,
        private val downloads: BackgroundDownloadQueuer,
        private val playerManager: Provider<EnhancedPlayerManager>,
    ) : ViewModel() {
        val userPlaylists: StateFlow<List<PlaylistInfo>> =
            playlists
                .getUserCreatedVideoPlaylistsFlow()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), emptyList())

        /** Songs go after the current song, videos after the current video, each kept in list order. */
        fun playNext(videos: List<Video>) {
            val (songs, others) = videos.distinctBy { it.id }.partition { it.isMusic }
            MultiSelection.playNextOrder(others).forEach { playerManager.get().addVideoToQueueNext(it) }
            MultiSelection.playNextOrder(songs).forEach { video ->
                EnhancedMusicPlayerManager.playNext(video.toMusicTrack())
                EnhancedMusicPlayerManager.removeAutomixItem(video.id)
            }
        }

        /** Queues every video not already downloaded or queued; runs on past the screen that asked. */
        fun download(videos: List<Video>) {
            if (videos.isEmpty()) return
            downloads.queueAll("selection-${System.currentTimeMillis()}", videos)
        }

        /** Adds [videos] to [playlistId] (ones already there stay put) and returns its name, or null when it failed. */
        suspend fun addToPlaylist(
            playlistId: String,
            videos: List<Video>,
        ): String? =
            runCatching {
                playlists.addVideosToPlaylist(playlistId, videos)
                playlists.getPlaylistInfo(playlistId)?.name.orEmpty()
            }.getOrNull()

        /** Makes a private video playlist named [name] holding [videos]; returns its name, or null when it failed. */
        suspend fun createPlaylistWith(
            name: String,
            description: String,
            videos: List<Video>,
        ): String? =
            runCatching {
                val playlistId = System.currentTimeMillis().toString()
                playlists.createPlaylist(playlistId, name, description, isPrivate = true)
                playlists.addVideosToPlaylist(playlistId, videos)
                name
            }.getOrNull()

        private companion object {
            const val SHARING_TIMEOUT_MS = 5_000L
        }
    }
