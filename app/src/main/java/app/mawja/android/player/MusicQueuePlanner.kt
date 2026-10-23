package app.mawja.android.player

internal object MusicQueuePlanner {
    const val INDEX_UNSET = -1

    fun currentQueueIndex(
        queueIds: List<String>,
        playerIndex: Int,
        currentTrackId: String?
    ): Int {
        if (playerIndex in queueIds.indices && queueIds[playerIndex] == currentTrackId) {
            return playerIndex
        }

        if (currentTrackId == null) return INDEX_UNSET

        return queueIds.indexOf(currentTrackId)
    }

    fun playNextInsertionIndex(
        queueIds: List<String>,
        playerIndex: Int,
        currentTrackId: String?
    ): Int {
        val currentIndex = currentQueueIndex(queueIds, playerIndex, currentTrackId)
        return if (currentIndex == INDEX_UNSET) {
            queueIds.size
        } else {
            (currentIndex + 1).coerceIn(0, queueIds.size)
        }
    }

    fun shouldForcePendingPlayNext(
        isAutomaticTransition: Boolean,
        pendingMediaId: String?,
        pendingPlayerIndex: Int,
        actualMediaId: String?,
        actualPlayerIndex: Int
    ): Boolean {
        val pendingIndexMissed = pendingPlayerIndex != INDEX_UNSET && pendingPlayerIndex != actualPlayerIndex
        return isAutomaticTransition &&
            pendingMediaId != null &&
            actualMediaId != null &&
            (actualMediaId != pendingMediaId || pendingIndexMissed)
    }

    /**
     * Shuffles [items] while avoiding the same artist twice in a row where possible:
     * random order first, then each slot takes an item whose artist differs from
     * [previousArtist]/the last placed one, preferring the artist with most tracks left (falls back to the next item
     * when only one artist is left). Blank artists never count as a repeat.
     */
    fun <T> shuffleAvoidingArtistRepeats(
        items: List<T>,
        previousArtist: String?,
        random: kotlin.random.Random = kotlin.random.Random.Default,
        artistOf: (T) -> String,
    ): List<T> {
        val pool = items.shuffled(random).toMutableList()
        val out = ArrayList<T>(pool.size)
        fun key(item: T) = artistOf(item).trim().lowercase()
        val remaining = pool.groupingBy { key(it) }.eachCount().toMutableMap()
        var last = previousArtist?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        while (pool.isNotEmpty()) {
            // Among items not repeating the last artist, prefer the artist with the most
            // tracks left (ties keep the random order) so we never paint into a corner.
            var idx = -1
            var best = -1
            pool.forEachIndexed { i, item ->
                val a = key(item)
                if (a.isNotEmpty() && a == last) return@forEachIndexed
                val c = if (a.isEmpty()) 0 else remaining[a] ?: 0
                if (c > best) {
                    best = c
                    idx = i
                }
            }
            val picked = pool.removeAt(if (idx >= 0) idx else 0)
            out += picked
            val a = key(picked)
            remaining[a] = (remaining[a] ?: 1) - 1
            last = a.takeIf { it.isNotEmpty() }
        }
        return out
    }
}
