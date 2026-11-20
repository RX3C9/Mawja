/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/** A video the planner may pick: [seconds] long, [score] how much the viewer wants it (any scale >= 0). */
data class BudgetCandidate(val id: String, val seconds: Int, val score: Double)

/** The pick for one budget: ids in watch order, their total length, and the leftover seconds. */
data class BudgetPlan(val ids: List<String>, val totalSeconds: Int, val leftoverSeconds: Int)

/**
 * "I have 15 minutes": picks the set of videos that fits the time and is worth the most, by an exact
 * 0/1 knapsack over 10-second buckets (fast enough for a 300-item feed on any phone). Ties prefer
 * the plan that leaves less time unused. Watch order puts the best-scored first.
 */
object TimeBudgetPlanner {
    private const val BUCKET = 10

    fun plan(
        candidates: List<BudgetCandidate>,
        budgetSeconds: Int,
    ): BudgetPlan {
        if (budgetSeconds <= 0) return BudgetPlan(emptyList(), 0, maxOf(0, budgetSeconds))
        val items = candidates.filter { it.seconds in 1..budgetSeconds && it.score >= 0.0 }.distinctBy { it.id }
        val cap = budgetSeconds / BUCKET
        val w = IntArray(items.size) { (items[it].seconds + BUCKET - 1) / BUCKET }
        val best = Array(items.size + 1) { DoubleArray(cap + 1) }
        for (i in 1..items.size) {
            val wi = w[i - 1]
            val vi = items[i - 1].score
            for (c in 0..cap) {
                var v = best[i - 1][c]
                if (wi <= c) v = maxOf(v, best[i - 1][c - wi] + vi)
                best[i][c] = v
            }
        }
        // Smallest capacity that reaches the best value = least wasted time for the same worth.
        val top = best[items.size][cap]
        var c = (0..cap).first { best[items.size][it] >= top - 1e-9 }
        val chosen = mutableListOf<BudgetCandidate>()
        for (i in items.size downTo 1) {
            if (best[i][c] != best[i - 1][c]) {
                chosen += items[i - 1]
                c -= w[i - 1]
            }
        }
        val ordered = chosen.sortedByDescending { it.score }
        val total = ordered.sumOf { it.seconds }
        return BudgetPlan(ordered.map { it.id }, total, budgetSeconds - total)
    }
}
