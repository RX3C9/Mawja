package app.mawja.android.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.mawja.android.R
import app.mawja.android.accessibility.AccessibilityPolicy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mawja.android.ui.components.shared.card.rememberWatchProgress
import app.mawja.android.ui.theme.artworkScrim

private const val PROGRESS_TRACK_ALPHA = 0.4f
private val ProgressBarHeight = 3.dp

@Composable
fun ThumbnailWatchProgress(
    videoId: String,
    modifier: Modifier = Modifier,
) {
    val progress = rememberWatchProgress(videoId) ?: return
    WatchProgressBar(progress = progress, modifier = modifier)
}

@Composable
fun WatchProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = ProgressBarHeight,
) {
    val watched = stringResource(R.string.a11y_watched_percent, AccessibilityPolicy.percentOf(progress))
    LinearProgressIndicator(
        progress = { progress },
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                // TalkBack (#160): "40% watched" inside the card, not a bare "40 percent".
                .semantics { contentDescription = watched },
        color = MaterialTheme.colorScheme.primary,
        trackColor = artworkScrim(PROGRESS_TRACK_ALPHA),
    )
}
