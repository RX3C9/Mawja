package app.mawja.android.data.ratings

/** What a rating is about. The stored id is `video:<id>` or `track:<id>`. */
enum class RatingKind {
    Video,
    Track,
    ;

    fun idFor(targetId: String): String = "${name.lowercase()}:$targetId"

    companion object {
        fun fromName(name: String): RatingKind? = entries.firstOrNull { it.name == name }
    }
}

/** What was rated, saved with the rating so a rated list renders offline. */
data class RatingSubject(
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0,
)

/** The viewer's own star rating for one video or track. [stars] is always in [Ratings.MIN_STARS]..[Ratings.MAX_STARS]. */
data class Rating(
    val targetId: String,
    val kind: RatingKind,
    val stars: Int,
    val updatedAt: Long,
    val subject: RatingSubject? = null,
) {
    val id: String get() = kind.idFor(targetId)
}

/** Pure rating rules: no Android, no storage. */
object Ratings {
    const val MIN_STARS = 1
    const val MAX_STARS = 5

    /** True for a value a row may hold. 0 or less means "no rating" and clears instead. */
    fun isValid(stars: Int): Boolean = stars in MIN_STARS..MAX_STARS

    /** Brings any input onto the 0..5 scale; 0 means cleared. */
    fun clamp(stars: Int): Int = stars.coerceIn(0, MAX_STARS)

    /**
     * What a tap on star [tapped] does to [current]: tapping the star already set clears the rating
     * (so the same gesture that sets it can undo it), anything else sets it. Returns 0 for cleared.
     */
    fun toggle(
        current: Int,
        tapped: Int,
    ): Int {
        val t = clamp(tapped)
        return if (t == clamp(current)) 0 else t
    }

    /** One step up or down, as an accessibility "set progress" or a d-pad would; never below 0. */
    fun step(
        current: Int,
        delta: Int,
    ): Int = clamp(clamp(current) + delta)

    /** Highest rating first, then most recently rated first, then id for a stable order. */
    val ORDER: Comparator<Rating> =
        compareByDescending<Rating> { it.stars }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id }

    fun sorted(ratings: Collection<Rating>): List<Rating> = ratings.filter { isValid(it.stars) }.sortedWith(ORDER)

    /**
     * targetId → stars, for a list that only needs to know whether and how an item was rated. A target
     * rated both as a video and as a track keeps the higher rating.
     */
    fun starsByTarget(
        ratings: Collection<Rating>,
        kind: RatingKind? = null,
    ): Map<String, Int> =
        ratings
            .asSequence()
            .filter { isValid(it.stars) && (kind == null || it.kind == kind) }
            .groupBy({ it.targetId }, { it.stars })
            .mapValues { (_, stars) -> stars.max() }
}
