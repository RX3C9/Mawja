package app.mawja.android.lab

import app.mawja.android.lab.WatchGuardian.Event
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchGuardianTest {
    @Test
    fun `nudges after an autoplay streak, then respects the cooldown`() {
        val g = WatchGuardian(autoplayStreak = 3, cooldownMs = 1000)
        assertThat(g.onEvent(Event.Started(0, false))).isFalse()
        assertThat(g.onEvent(Event.Started(10, true))).isFalse()
        assertThat(g.onEvent(Event.Started(20, true))).isFalse()
        assertThat(g.onEvent(Event.Started(30, true))).isTrue()
        repeat(3) { assertThat(g.onEvent(Event.Started(40L + it, true))).isFalse() }
    }

    @Test
    fun `choosing a video resets the streak`() {
        val g = WatchGuardian(autoplayStreak = 2)
        g.onEvent(Event.Started(0, true))
        g.onEvent(Event.UserChose(5))
        assertThat(g.onEvent(Event.Started(10, true))).isFalse()
    }

    @Test
    fun `a long session triggers once and a real pause resets it`() {
        val g = WatchGuardian(sessionMs = 100, cooldownMs = 10_000)
        g.onEvent(Event.Started(0, false))
        assertThat(g.onEvent(Event.Started(150, false))).isTrue()
        g.onEvent(Event.Paused(200, 20 * 60_000L))
        assertThat(g.onEvent(Event.Started(300, false))).isFalse()
    }
}
