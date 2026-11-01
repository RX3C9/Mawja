package app.mawja.android.data.local

/**
 * Focus mode (#154): one switch that hides the Home recommendations, Shorts, comments and the
 * related videos under the player, leaving search and subscriptions. It is a preset over the four
 * existing settings rather than a filter on top of them, so each setting keeps showing its real
 * value and can still be changed on its own. Turning focus mode on remembers the four values it
 * replaced; turning it off puts them back.
 */
internal data class FocusModeSettings(
    val homeFeed: Boolean = true,
    val shorts: Boolean = true,
    val comments: Boolean = true,
    val relatedVideos: Boolean = true,
) {
    /** Four 0/1 digits in a fixed order: home feed, Shorts, comments, related videos. */
    fun encode(): String = listOf(homeFeed, shorts, comments, relatedVideos).joinToString("") { if (it) "1" else "0" }

    companion object {
        /** Everything focus mode hides, hidden. */
        val FOCUSED = FocusModeSettings(homeFeed = false, shorts = false, comments = false, relatedVideos = false)

        /** Reads [encode]'s output; anything else (missing, cut short, edited) restores the defaults. */
        fun decode(raw: String?): FocusModeSettings {
            if (raw == null || raw.length != 4 || raw.any { it != '0' && it != '1' }) return FocusModeSettings()
            return FocusModeSettings(raw[0] == '1', raw[1] == '1', raw[2] == '1', raw[3] == '1')
        }
    }
}

/** What a focus mode switch writes: the settings to apply and the snapshot to keep (null clears it). */
internal data class FocusModeChange(
    val apply: FocusModeSettings,
    val snapshot: String?,
)

internal object FocusMode {
    /**
     * [current] is what the four settings hold now and [snapshot] what was saved when focus mode
     * was last turned on. Turning it on twice keeps the first snapshot, so the values from before
     * focus mode are never overwritten by focus mode's own. Turning it off with no snapshot (an old
     * backup, a cleared key) brings everything back on, the app's defaults.
     */
    fun change(
        enable: Boolean,
        current: FocusModeSettings,
        snapshot: String?,
    ): FocusModeChange =
        if (enable) {
            FocusModeChange(apply = FocusModeSettings.FOCUSED, snapshot = snapshot ?: current.encode())
        } else {
            FocusModeChange(apply = FocusModeSettings.decode(snapshot), snapshot = null)
        }
}
