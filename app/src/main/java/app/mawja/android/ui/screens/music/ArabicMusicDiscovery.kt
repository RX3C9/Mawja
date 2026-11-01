package app.mawja.android.ui.screens.music

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.mawja.android.R
import app.mawja.android.ui.components.music.common.MusicMoodButton
import app.mawja.android.ui.components.music.common.MusicMoodTone
import app.mawja.android.ui.components.music.header.MusicSectionHeader
import app.mawja.android.ui.theme.Dimensions

/**
 * Arabic music discovery (#148): hand-picked sections of Arabic music on the music search page. Each
 * one runs a music search with an Arabic query, whatever the app language, because that is how the
 * songs are titled and tagged; the label follows the app language.
 */
internal data class ArabicMusicSection(
    val id: String,
    @StringRes val label: Int,
    val query: String,
)

internal object ArabicMusicDiscovery {
    val sections =
        listOf(
            ArabicMusicSection("tarab", R.string.arabic_music_tarab, "طرب أصيل"),
            ArabicMusicSection("mahraganat", R.string.arabic_music_mahraganat, "مهرجانات"),
            ArabicMusicSection("khaleeji", R.string.arabic_music_khaleeji, "أغاني خليجية"),
            ArabicMusicSection("maghrebi", R.string.arabic_music_maghrebi, "أغاني مغربية"),
            ArabicMusicSection("levantine", R.string.arabic_music_levantine, "أغاني شامية"),
            ArabicMusicSection("indie", R.string.arabic_music_indie, "إندي عربي"),
        )
}

@Composable
internal fun ArabicMusicShelf(
    onSectionClick: (ArabicMusicSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MusicSectionHeader(title = stringResource(R.string.arabic_music_title))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimensions.ContentPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.ItemSpacing),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(items = ArabicMusicDiscovery.sections, key = { _, section -> section.id }) { index, section ->
                MusicMoodButton(
                    title = stringResource(section.label),
                    onClick = { onSectionClick(section) },
                    tone = MusicMoodTone.forIndex(index),
                )
            }
        }
    }
}
