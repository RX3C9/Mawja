package app.mawja.android.ui.components.shared.rating

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.data.ratings.RatingKind
import app.mawja.android.data.ratings.RatingSubject
import app.mawja.android.data.ratings.Ratings
import kotlin.math.roundToInt

/**
 * Five stars for the viewer's own rating (#152). [stars] is 0 when unrated. Tapping a star sets it;
 * tapping the set star clears it. TalkBack reads one node, "N of 5 stars", adjustable like a slider
 * (swipe up/down or volume keys) with a Clear rating action. Outline is onSurface and fill is primary
 * so both read in high contrast, and every star is a 48dp target.
 */
@Composable
fun RatingBar(
    stars: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val value = Ratings.clamp(stars)
    val latestOnRate by rememberUpdatedState(onRate)
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.rating_label)
    val state =
        if (value == 0) {
            stringResource(R.string.rating_not_rated)
        } else {
            pluralStringResource(R.plurals.rating_n_of_5, value, value)
        }
    val clearLabel = stringResource(R.string.rating_clear)
    Row(
        modifier =
            modifier.clearAndSetSemantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        current = value.toFloat(),
                        range = 0f..Ratings.MAX_STARS.toFloat(),
                        steps = Ratings.MAX_STARS - 1,
                    )
                if (enabled) {
                    setProgress { target ->
                        val next = Ratings.clamp(target.roundToInt())
                        if (next != value) latestOnRate(next)
                        true
                    }
                    if (value > 0) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(clearLabel) {
                                    latestOnRate(0)
                                    true
                                },
                            )
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (star in 1..Ratings.MAX_STARS) {
            val filled = star <= value
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnRate(Ratings.toggle(value, star))
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * The "My rating" row of a menu group: the bar plus a Clear button once rated. Reads and writes
 * through the shared [RatingsViewModel].
 */
@Composable
fun MyRatingRow(
    kind: RatingKind,
    targetId: String,
    subject: RatingSubject?,
    shape: Shape,
    modifier: Modifier = Modifier,
    viewModel: RatingsViewModel = sharedRatingsViewModel(),
) {
    val ratings by viewModel.ratings.collectAsStateWithLifecycle()
    val stars = ratings[kind.idFor(targetId)]?.stars ?: 0
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            RatingBar(
                stars = stars,
                onRate = { viewModel.setRating(kind, targetId, it, subject) },
            )
            Spacer(Modifier.weight(1f))
            if (stars > 0) {
                TextButton(onClick = { viewModel.clear(kind, targetId) }) {
                    Text(stringResource(R.string.rating_clear))
                }
            }
        }
    }
}
