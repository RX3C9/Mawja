/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class OrderKeysTest {
    private fun assertKey(key: String) {
        assertThat(OrderKeys.isValid(key)).isTrue()
    }

    @Test
    fun betweenIsStrictlyBetweenAndValid() {
        val first = OrderKeys.between(null, null)
        assertKey(first)
        val after = OrderKeys.between(first, null)
        val before = OrderKeys.between(null, first)
        assertThat(after).isGreaterThan(first)
        assertThat(before).isLessThan(first)
        val mid = OrderKeys.between(before, first)
        assertThat(mid).isGreaterThan(before)
        assertThat(mid).isLessThan(first)
        listOf(after, before, mid).forEach(::assertKey)
        // Neighbouring digits and long shared prefixes still leave room.
        assertThat(OrderKeys.between("1", "2")).isGreaterThan("1")
        assertThat(OrderKeys.between("1", "2")).isLessThan("2")
        assertThat(OrderKeys.between("zz", null)).isGreaterThan("zz")
        assertThat(OrderKeys.between(null, "01")).isLessThan("01")
    }

    @Test
    fun randomInsertsStaySortedAndShort() {
        val random = Random(42)
        val keys = ArrayList<String>()
        repeat(5_000) {
            val at = random.nextInt(keys.size + 1)
            val key = OrderKeys.between(keys.getOrNull(at - 1), keys.getOrNull(at))
            keys.add(at, key)
        }
        assertThat(keys).isInStrictOrder()
        keys.forEach(::assertKey)
        assertThat(keys.maxOf { it.length }).isAtMost(12)
    }

    @Test
    fun appendsAndPrependsGrowSlowly() {
        var last: String? = null
        repeat(1_000) { last = OrderKeys.between(last, null) }
        assertThat(last!!.length).isAtMost(40)
        var first: String? = null
        repeat(1_000) { first = OrderKeys.between(null, first) }
        assertThat(first!!.length).isAtMost(40)
    }

    @Test
    fun keysBetweenSpreadsEvenly() {
        val keys = OrderKeys.keysBetween(null, null, 3_000)
        assertThat(keys).hasSize(3_000)
        assertThat(keys).isInStrictOrder()
        assertThat(keys.maxOf { it.length }).isAtMost(8)
        val inside = OrderKeys.keysBetween("A", "B", 50)
        assertThat(inside.first()).isGreaterThan("A")
        assertThat(inside.last()).isLessThan("B")
    }

    @Test
    fun assignKeepsUnchangedKeysAndOnlyRekeysMovedOrNewEntries() {
        val ids = listOf("a", "b", "c", "d", "e")
        val initial = OrderKeys.assign(ids, emptyMap())
        assertThat(initial).isInStrictOrder()
        val prior = ids.zip(initial).toMap()

        // Same order: nothing changes.
        assertThat(OrderKeys.assign(ids, prior)).isEqualTo(initial)

        // "e" moved to the front, "x" inserted after "b": only those two get new keys.
        val moved = listOf("e", "a", "b", "x", "c", "d")
        val keys = OrderKeys.assign(moved, prior)
        assertThat(keys).isInStrictOrder()
        val byId = moved.zip(keys).toMap()
        listOf("a", "b", "c", "d").forEach { assertThat(byId[it]).isEqualTo(prior[it]) }
        assertThat(byId["e"]).isNotEqualTo(prior["e"])
    }

    @Test
    fun assignIgnoresInvalidPriorKeys() {
        val keys = OrderKeys.assign(listOf("a", "b"), mapOf("a" to "bad-key", "b" to "V0"))
        assertThat(keys).isInStrictOrder()
        keys.forEach(::assertKey)
    }

    @Test
    fun longestIncreasingPicksAMaximalRun() {
        val run = OrderKeys.longestIncreasing(listOf("5", null, "1", "2", "9", "3", "4"))
        assertThat(run).containsExactly(2, 3, 5, 6).inOrder()
        assertThat(OrderKeys.longestIncreasing(listOf(null, null))).isEmpty()
    }

    @Test
    fun textMapCodecRoundTripsAndSkipsDamage() {
        val map = mapOf("pl.v1" to "V", "pl.v2" to "Vz")
        val text = SyncTextMapCodec.encode(map)
        assertThat(SyncTextMapCodec.decode(text, OrderKeys::isValid)).isEqualTo(map)
        assertThat(SyncTextMapCodec.decode("$text\nbad line\nk\tV0\n bad key\tV", OrderKeys::isValid)).isEqualTo(map)
        assertThat(SyncTextMapCodec.decode(null)).isEmpty()
    }
}
