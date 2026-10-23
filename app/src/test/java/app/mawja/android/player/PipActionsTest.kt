package app.mawja.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PipActionsTest {
    @Test
    fun `a queue puts the skip buttons either side of pause`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = PipQueueNavigation(hasPrevious = true, hasNext = true), maxActions = 3)

        assertEquals(listOf(PipControl.PREVIOUS, PipControl.PAUSE, PipControl.NEXT), specs.map { it.control })
        assertEquals(listOf(true, true, true), specs.map { it.enabled })
    }

    @Test
    fun `the first reel greys out previous and the last greys out next`() {
        val first = pipActionSpecs(isPlaying = false, navigation = PipQueueNavigation(hasPrevious = false, hasNext = true), maxActions = 3)
        val last = pipActionSpecs(isPlaying = true, navigation = PipQueueNavigation(hasPrevious = true, hasNext = false), maxActions = 3)

        assertEquals(PipControl.PLAY, first[1].control)
        assertEquals(listOf(false, true, true), first.map { it.enabled })
        assertEquals(listOf(true, true, false), last.map { it.enabled })
    }

    @Test
    fun `without a queue the window keeps its single play or pause button`() {
        assertEquals(listOf(PipActionSpec(PipControl.PAUSE)), pipActionSpecs(isPlaying = true, navigation = null, maxActions = 3))
        assertEquals(listOf(PipActionSpec(PipControl.PLAY)), pipActionSpecs(isPlaying = false, navigation = null, maxActions = 3))
    }

    @Test
    fun `a window with room for fewer than three buttons drops the skip buttons`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = PipQueueNavigation(hasPrevious = true, hasNext = true), maxActions = 2)

        assertEquals(listOf(PipActionSpec(PipControl.PAUSE)), specs)
    }

    @Test
    fun `the video player shows rewind, play-pause and forward`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = null, maxActions = 3, seekButtons = true)

        assertEquals(listOf(PipControl.REWIND, PipControl.PAUSE, PipControl.FORWARD), specs.map { it.control })
    }

    @Test
    fun `with five slots and a queue the skip buttons go on the outside`() {
        val specs =
            pipActionSpecs(
                isPlaying = false,
                navigation = PipQueueNavigation(hasPrevious = false, hasNext = true),
                maxActions = 5,
                seekButtons = true,
            )

        assertEquals(
            listOf(PipControl.PREVIOUS, PipControl.REWIND, PipControl.PLAY, PipControl.FORWARD, PipControl.NEXT),
            specs.map { it.control },
        )
        assertEquals(listOf(false, true, true, true, true), specs.map { it.enabled })
    }

    @Test
    fun `with three slots the seek buttons win over the queue`() {
        val specs =
            pipActionSpecs(
                isPlaying = true,
                navigation = PipQueueNavigation(hasPrevious = true, hasNext = true),
                maxActions = 3,
                seekButtons = true,
            )

        assertEquals(listOf(PipControl.REWIND, PipControl.PAUSE, PipControl.FORWARD), specs.map { it.control })
    }

    @Test
    fun `a tiny window keeps only play-pause even with seek buttons`() {
        assertEquals(listOf(PipActionSpec(PipControl.PLAY)), pipActionSpecs(false, null, 1, seekButtons = true))
    }

    @Test
    fun `seek target stays inside the video`() {
        assertEquals(15_000L, pipSeekTarget(5_000L, 60_000L, forward = true))
        assertEquals(0L, pipSeekTarget(4_000L, 60_000L, forward = false))
        assertEquals(60_000L, pipSeekTarget(55_000L, 60_000L, forward = true))
        assertEquals(20_000L, pipSeekTarget(10_000L, -1L, forward = true))
    }
}
