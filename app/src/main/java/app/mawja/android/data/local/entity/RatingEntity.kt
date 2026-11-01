package app.mawja.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The viewer's own star rating (1–5) for a video or a track. [id] is `video:<id>` or `track:<id>` so
 * one table serves both and a lookup is a primary-key hit. Clearing a rating deletes the row.
 *
 * [title] onwards describe what was rated, saved with it so a rated list renders without a network call.
 */
@Entity(tableName = "ratings")
data class RatingEntity(
    @PrimaryKey val id: String,
    val targetId: String,
    val kind: String,
    val stars: Int,
    val updatedAt: Long,
    val title: String? = null,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
)
