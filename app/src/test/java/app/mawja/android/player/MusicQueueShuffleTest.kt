package app.mawja.android.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicQueueShuffleTest {
    private data class T(val id: Int, val artist: String)

    private fun shuffle(items: List<T>, prev: String?, seed: Int) =
        MusicQueuePlanner.shuffleAvoidingArtistRepeats(items, prev, Random(seed)) { it.artist }

    @Test
    fun keepsEveryItemExactlyOnce() {
        val items = (1..20).map { T(it, "a${it % 4}") }
        repeat(10) { seed ->
            assertEquals(items.toSet(), shuffle(items, null, seed).toSet())
            assertEquals(items.size, shuffle(items, null, seed).size)
        }
    }

    @Test
    fun noAdjacentSameArtistWhenAvoidable() {
        val items = (1..12).map { T(it, listOf("A", "B", "C")[it % 3]) }
        repeat(25) { seed ->
            val out = shuffle(items, "A", seed)
            assertTrue(out.first().artist != "A")
            out.zipWithNext().forEach { (x, y) -> assertTrue(x.artist != y.artist) }
        }
    }

    @Test
    fun artistMatchIsCaseAndSpaceInsensitive() {
        val items = listOf(T(1, " adele"), T(2, "Muse"))
        repeat(10) { seed -> assertEquals(2, shuffle(items, "Adele", seed).first().id) }
    }

    @Test
    fun singleArtistFallsBackGracefully() {
        val items = (1..5).map { T(it, "Solo") }
        assertEquals(5, shuffle(items, "Solo", 1).size)
    }

    @Test
    fun emptyInputGivesEmpty() {
        assertTrue(shuffle(emptyList(), null, 0).isEmpty())
    }
}
