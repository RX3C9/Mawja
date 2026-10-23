package app.mawja.android.player

/** Whether the queue behind a PiP window can step back or forward. */
data class PipQueueNavigation(
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

internal enum class PipControl { PREVIOUS, REWIND, PLAY, PAUSE, FORWARD, NEXT }

internal data class PipActionSpec(
    val control: PipControl,
    val enabled: Boolean = true,
)

private const val SKIP_LAYOUT_ACTION_COUNT = 3
private const val FULL_LAYOUT_ACTION_COUNT = 5

/** How far the PiP rewind / forward buttons jump (#94). */
const val PIP_SEEK_STEP_MS = 10_000L

/**
 * The window's buttons in display order. Skip buttons need a queue and room for all three.
 *
 * With [seekButtons] (the main video player, #94) the window shows −10 s / play-pause / +10 s; when
 * the system allows five actions and there is a queue, previous and next go on the outside. Most
 * phones allow three, so there the ±10 s buttons win over the queue buttons. Live streams pass
 * [seekButtons] = false.
 */
internal fun pipActionSpecs(
    isPlaying: Boolean,
    navigation: PipQueueNavigation?,
    maxActions: Int,
    seekButtons: Boolean = false,
): List<PipActionSpec> {
    val playPause = PipActionSpec(if (isPlaying) PipControl.PAUSE else PipControl.PLAY)
    if (seekButtons && maxActions >= SKIP_LAYOUT_ACTION_COUNT) {
        val seekRow = listOf(PipActionSpec(PipControl.REWIND), playPause, PipActionSpec(PipControl.FORWARD))
        if (navigation == null || maxActions < FULL_LAYOUT_ACTION_COUNT) return seekRow
        return listOf(PipActionSpec(PipControl.PREVIOUS, enabled = navigation.hasPrevious)) +
            seekRow +
            PipActionSpec(PipControl.NEXT, enabled = navigation.hasNext)
    }
    if (navigation == null || maxActions < SKIP_LAYOUT_ACTION_COUNT) return listOf(playPause)
    return listOf(
        PipActionSpec(PipControl.PREVIOUS, enabled = navigation.hasPrevious),
        playPause,
        PipActionSpec(PipControl.NEXT, enabled = navigation.hasNext),
    )
}

/** Where a ±[PIP_SEEK_STEP_MS] jump lands: never before 0, never past the end when it is known. */
internal fun pipSeekTarget(
    positionMs: Long,
    durationMs: Long,
    forward: Boolean,
): Long {
    val raw = if (forward) positionMs + PIP_SEEK_STEP_MS else positionMs - PIP_SEEK_STEP_MS
    val end = if (durationMs > 0) durationMs else Long.MAX_VALUE
    return raw.coerceIn(0L, end)
}
