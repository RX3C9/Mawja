package app.mawja.android.data.ratings

import app.mawja.android.data.local.entity.RatingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsTest {
    private fun rating(
        id: String,
        stars: Int,
        at: Long,
        kind: RatingKind = RatingKind.Video,
    ) = Rating(targetId = id, kind = kind, stars = stars, updatedAt = at)

    @Test
    fun `ids are kind-prefixed`() {
        assertEquals("video:abc", RatingKind.Video.idFor("abc"))
        assertEquals("track:abc", RatingKind.Track.idFor("abc"))
        assertEquals(RatingKind.Track, RatingKind.fromName("Track"))
        assertNull(RatingKind.fromName("track"))
    }

    @Test
    fun `only one to five is a stored rating`() {
        assertFalse(Ratings.isValid(0))
        assertTrue(Ratings.isValid(1))
        assertTrue(Ratings.isValid(5))
        assertFalse(Ratings.isValid(6))
        assertFalse(Ratings.isValid(-1))
    }

    @Test
    fun `clamp brings input onto zero to five`() {
        assertEquals(0, Ratings.clamp(-3))
        assertEquals(3, Ratings.clamp(3))
        assertEquals(5, Ratings.clamp(9))
    }

    @Test
    fun `tapping the set star clears, any other sets`() {
        assertEquals(4, Ratings.toggle(current = 0, tapped = 4))
        assertEquals(2, Ratings.toggle(current = 4, tapped = 2))
        assertEquals(0, Ratings.toggle(current = 4, tapped = 4))
        assertEquals(5, Ratings.toggle(current = 3, tapped = 8))
    }

    @Test
    fun `step stays within zero to five`() {
        assertEquals(1, Ratings.step(0, 1))
        assertEquals(5, Ratings.step(5, 1))
        assertEquals(0, Ratings.step(1, -1))
        assertEquals(0, Ratings.step(0, -1))
    }

    @Test
    fun `sorted is highest rating first then most recent and drops invalid`() {
        val list =
            listOf(
                rating("a", 3, at = 10),
                rating("b", 5, at = 1),
                rating("c", 5, at = 7),
                rating("d", 0, at = 99),
                rating("e", 3, at = 20),
            )
        assertEquals(listOf("c", "b", "e", "a"), Ratings.sorted(list).map { it.targetId })
    }

    @Test
    fun `stars by target keeps the higher of a video and track rating`() {
        val list =
            listOf(
                rating("x", 2, at = 1, kind = RatingKind.Video),
                rating("x", 4, at = 2, kind = RatingKind.Track),
                rating("y", 3, at = 3),
            )
        assertEquals(mapOf("x" to 4, "y" to 3), Ratings.starsByTarget(list))
        assertEquals(mapOf("x" to 2, "y" to 3), Ratings.starsByTarget(list, RatingKind.Video))
    }

    @Test
    fun `entity round trip keeps subject and rejects bad rows`() {
        val subject = RatingSubject(title = "T", subtitle = "S", thumbnailUrl = "u", durationSeconds = 42)
        val original = Rating("v1", RatingKind.Video, 4, 123L, subject)
        val entity = original.toEntity()
        assertEquals("video:v1", entity.id)
        assertEquals(original, entity.toRating())
        assertNull(entity.copy(stars = 0).toRating())
        assertNull(entity.copy(kind = "Channel").toRating())
        val bare = RatingEntity(id = "track:t", targetId = "t", kind = "Track", stars = 1, updatedAt = 5)
        assertNull(bare.toRating()?.subject)
    }
}
