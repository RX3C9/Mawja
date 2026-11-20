package app.mawja.android.lab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeBudgetPlannerTest {
    @Test
    fun `picks the most valuable set that fits`() {
        val c = listOf(
            BudgetCandidate("a", 600, 5.0),
            BudgetCandidate("b", 400, 4.0),
            BudgetCandidate("c", 500, 4.5),
            BudgetCandidate("d", 950, 9.0),
        )
        val p = TimeBudgetPlanner.plan(c, 900)
        assertThat(p.ids.toSet()).isEqualTo(setOf("b", "c"))
        assertThat(p.totalSeconds).isAtMost(900)
        assertThat(p.leftoverSeconds).isEqualTo(0)
    }

    @Test
    fun `never exceeds the budget and orders best first`() {
        val c = (1..60).map { BudgetCandidate("v$it", 60 + it * 7, (it % 9).toDouble()) }
        val p = TimeBudgetPlanner.plan(c, 1800)
        assertThat(p.totalSeconds).isAtMost(1800)
        val scores = p.ids.map { id -> c.first { it.id == id }.score }
        assertThat(scores).isInOrder(Comparator.reverseOrder<Double>())
    }

    @Test
    fun `empty or zero budget gives an empty plan`() {
        assertThat(TimeBudgetPlanner.plan(emptyList(), 600).ids).isEmpty()
        assertThat(TimeBudgetPlanner.plan(listOf(BudgetCandidate("a", 30, 1.0)), 0).ids).isEmpty()
    }
}
