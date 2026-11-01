package app.mawja.android.ui.components.shared.quickactions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mawja.android.R
import app.mawja.android.data.ratings.RatingKind
import app.mawja.android.data.ratings.RatingSubject
import app.mawja.android.ui.components.shared.rating.MyRatingRow

/** The "My rating" group of a video or track menu (#152). Draws nothing for a blank [targetId]. */
@Composable
fun MyRatingGroup(
    kind: RatingKind,
    targetId: String,
    subject: RatingSubject?,
) {
    if (targetId.isBlank()) return
    QuickActionsGroup(
        title = stringResource(R.string.rating_my_rating),
        rows =
            listOf(
                QuickActionRow("my_rating") { shape ->
                    MyRatingRow(kind = kind, targetId = targetId, subject = subject, shape = shape)
                },
            ),
    )
}
