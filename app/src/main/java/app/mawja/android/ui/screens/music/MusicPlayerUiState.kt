package app.mawja.android.ui.screens.music

import app.mawja.android.data.local.LYRICS_ALIGN_CENTER
import app.mawja.android.data.lyrics.LyricsCandidate
import app.mawja.android.data.lyrics.LyricsEntry
import app.mawja.android.data.music.model.MusicTrack
import app.mawja.android.player.RepeatMode

data class MusicPlayerUiState(
    val currentTrack: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0,
    val queue: List<MusicTrack> = emptyList(),
    val autoplaySuggestions: List<MusicTrack> = emptyList(),
    val currentQueueIndex: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isLiked: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: List<LyricsEntry> = emptyList(),
    val isLyricsLoading: Boolean = false,
    val playingFrom: String = "",
    val endlessRadioEnabled: Boolean = true,
    val relatedContent: List<MusicTrack> = emptyList(),
    val isRelatedLoading: Boolean = false,
    val isRadioLoading: Boolean = false,
    val downloadedTrackIds: Set<String> = emptySet(),
    val lyricsProviderName: String = "",
    val lyricsSyncOffsetMs: Long = 0L,
    val lyricsTextAlign: String = LYRICS_ALIGN_CENTER,
    val lyricsShowTranslation: Boolean = true,
    val lyricsShowRomanization: Boolean = true,
    val lyricsAutoRomanize: Boolean = false,
    val lyricsKeepScreenOn: Boolean = false,
    val lyricsCandidates: List<LyricsCandidate> = emptyList(),
    val isBrowsingLyrics: Boolean = false,
)
