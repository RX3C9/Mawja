/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

/**
 * Why Mawja Downloader refuses a new transfer, from its config/downloader. [code] is the wire value
 * the downloader sends back over binder (MawjaDownloaderContract.ERROR_*; a test keeps them equal).
 */
enum class DownloadBlock(
    val code: Int,
) {
    NONE(0),
    KILLED(1001),
    MAINTENANCE(1002),
    UPDATE_REQUIRED(1003),
    DOWNLOADS_DISABLED(1004),
    ;

    val blocks: Boolean get() = this != NONE

    companion object {
        /** The block a wire code stands for; null for 0 or an unknown (newer) code. */
        fun fromCode(code: Int): DownloadBlock? = entries.firstOrNull { it.code == code && it != NONE }

        /** Gate first (kill > maintenance > update), then the [feature] switch (default "downloads"). */
        fun of(
            settings: RemoteSettings,
            versionCode: Long,
            feature: String = DOWNLOADS,
        ): DownloadBlock =
            when (settings.gate(versionCode)) {
                RemoteGate.Killed -> KILLED
                is RemoteGate.Maintenance -> MAINTENANCE
                is RemoteGate.ForceUpdate -> UPDATE_REQUIRED
                RemoteGate.Open -> if (settings.isEnabled(feature)) NONE else DOWNLOADS_DISABLED
            }

        const val DOWNLOADS = "downloads"
    }
}

/**
 * Lets transfers that were already running finish when the downloader becomes blocked: a job seen
 * while downloads were open keeps getting its requests as long as it asks again within [graceMs].
 * Anything new (or idle for longer, or without a job id) gets the block.
 */
class TransferAdmission(
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {
    private val lastSeen = HashMap<String, Long>()

    @Synchronized
    fun admit(
        jobId: String?,
        block: DownloadBlock,
        nowMs: Long,
    ): DownloadBlock {
        lastSeen.entries.removeAll { nowMs - it.value > graceMs }
        val id = jobId?.trim()?.takeIf { it.isNotEmpty() }
        if (!block.blocks) {
            id?.let { lastSeen[it] = nowMs }
            return DownloadBlock.NONE
        }
        if (id != null && id in lastSeen) {
            lastSeen[id] = nowMs
            return DownloadBlock.NONE
        }
        return block
    }

    companion object {
        const val DEFAULT_GRACE_MS = 30 * 60 * 1000L
    }
}
