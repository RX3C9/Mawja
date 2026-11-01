package app.mawja.android.data.ratings

import app.mawja.android.data.local.dao.RatingDao
import app.mawja.android.data.local.entity.RatingEntity
import app.mawja.android.data.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

fun Video.toRatingSubject(): RatingSubject =
    RatingSubject(
        title = title,
        subtitle = channelName,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = duration,
    )

@Singleton
class RatingsRepository
    @Inject
    constructor(
        private val ratingDao: RatingDao,
    ) {
        /** Every rating, highest first then newest. */
        fun observeAll(): Flow<List<Rating>> = ratingDao.observeAll().map { rows -> Ratings.sorted(rows.mapNotNull { it.toRating() }) }

        fun observe(
            kind: RatingKind,
            targetId: String,
        ): Flow<Rating?> = ratingDao.observe(kind.idFor(targetId)).map { it?.toRating() }

        suspend fun get(
            kind: RatingKind,
            targetId: String,
        ): Rating? = ratingDao.get(kind.idFor(targetId))?.toRating()

        /**
         * Sets the rating; 0 (or anything outside 1..5 after clamping to 0) clears it by deleting the row.
         * A save with no [subject] keeps the one already stored.
         */
        suspend fun set(
            kind: RatingKind,
            targetId: String,
            stars: Int,
            subject: RatingSubject? = null,
        ) {
            if (targetId.isBlank()) return
            val id = kind.idFor(targetId)
            val value = Ratings.clamp(stars)
            if (!Ratings.isValid(value)) {
                ratingDao.deleteById(id)
                return
            }
            val stored = ratingDao.get(id)?.toRating()
            ratingDao.upsert(
                Rating(targetId, kind, value, System.currentTimeMillis(), subject ?: stored?.subject).toEntity(),
            )
        }

        suspend fun clear(
            kind: RatingKind,
            targetId: String,
        ) = ratingDao.deleteById(kind.idFor(targetId))

        suspend fun allEntities(): List<RatingEntity> = ratingDao.getAll()

        suspend fun restoreEntities(rows: List<RatingEntity>) {
            val valid = rows.filter { Ratings.isValid(it.stars) && RatingKind.fromName(it.kind) != null }
            if (valid.isNotEmpty()) ratingDao.upsertAll(valid)
        }
    }

internal fun Rating.toEntity(): RatingEntity =
    RatingEntity(
        id = kind.idFor(targetId),
        targetId = targetId,
        kind = kind.name,
        stars = stars,
        updatedAt = updatedAt,
        title = subject?.title,
        subtitle = subject?.subtitle,
        thumbnailUrl = subject?.thumbnailUrl,
        durationSeconds = subject?.durationSeconds,
    )

internal fun RatingEntity.toRating(): Rating? {
    val parsedKind = RatingKind.fromName(kind) ?: return null
    if (!Ratings.isValid(stars)) return null
    val subject =
        title?.takeIf { it.isNotBlank() }?.let {
            RatingSubject(
                title = it,
                subtitle = subtitle.orEmpty(),
                thumbnailUrl = thumbnailUrl.orEmpty(),
                durationSeconds = durationSeconds ?: 0,
            )
        }
    return Rating(targetId = targetId, kind = parsedKind, stars = stars, updatedAt = updatedAt, subject = subject)
}
