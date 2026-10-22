/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.video.downloader.remote

import app.mawja.android.R
import app.mawja.android.data.video.downloader.work.FetchFailure
import app.mawja.downloader.api.MawjaDownloaderContract
import app.mawja.remote.DownloadBlock
import app.mawja.remote.RemoteSettings
import app.mawja.remote.TransferAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderBlocksTest {
    @Test
    fun wireCodesMatchTheContract() {
        assertEquals(MawjaDownloaderContract.ERROR_REMOTE_BLOCKED, DownloadBlock.KILLED.code)
        assertEquals(MawjaDownloaderContract.ERROR_MAINTENANCE, DownloadBlock.MAINTENANCE.code)
        assertEquals(MawjaDownloaderContract.ERROR_UPDATE_REQUIRED, DownloadBlock.UPDATE_REQUIRED.code)
        assertEquals(MawjaDownloaderContract.ERROR_DOWNLOADS_DISABLED, DownloadBlock.DOWNLOADS_DISABLED.code)
        assertEquals(0, DownloadBlock.NONE.code)
        DownloadBlock.entries.filter { it.blocks }.forEach { assertEquals(it, DownloadBlock.fromCode(it.code)) }
        assertNull(DownloadBlock.fromCode(0))
        assertNull(DownloadBlock.fromCode(4242))
    }

    @Test
    fun blockFollowsGateThenDownloadsSwitch() {
        val v = 19L
        assertEquals(DownloadBlock.NONE, DownloadBlock.of(RemoteSettings.DEFAULT, v))
        assertEquals(DownloadBlock.KILLED, DownloadBlock.of(RemoteSettings(killSwitch = true, maintenance = true), v))
        assertEquals(DownloadBlock.MAINTENANCE, DownloadBlock.of(RemoteSettings(maintenance = true, minVersion = 99), v))
        assertEquals(DownloadBlock.UPDATE_REQUIRED, DownloadBlock.of(RemoteSettings(minVersion = 20, updateUrl = "https://x.example"), v))
        assertEquals(DownloadBlock.NONE, DownloadBlock.of(RemoteSettings(minVersion = 20), 0L))
        assertEquals(DownloadBlock.DOWNLOADS_DISABLED, DownloadBlock.of(RemoteSettings(features = mapOf("downloads" to false)), v))
        assertEquals(DownloadBlock.NONE, DownloadBlock.of(RemoteSettings(features = mapOf("retag" to false)), v))
        // The gate wins over the switch.
        assertEquals(DownloadBlock.KILLED, DownloadBlock.of(RemoteSettings(killSwitch = true, features = mapOf("downloads" to false)), v))
    }

    @Test
    fun appMapsEachCodeToAFailureAndMessage() {
        assertNull(DownloaderBlocks.failureFor(0))
        assertEquals(FetchFailure.DOWNLOADER_KILLED, DownloaderBlocks.failureFor(MawjaDownloaderContract.ERROR_REMOTE_BLOCKED))
        assertEquals(FetchFailure.DOWNLOADER_MAINTENANCE, DownloaderBlocks.failureFor(MawjaDownloaderContract.ERROR_MAINTENANCE))
        assertEquals(FetchFailure.DOWNLOADER_UPDATE_REQUIRED, DownloaderBlocks.failureFor(MawjaDownloaderContract.ERROR_UPDATE_REQUIRED))
        assertEquals(FetchFailure.DOWNLOADER_DISABLED, DownloaderBlocks.failureFor(MawjaDownloaderContract.ERROR_DOWNLOADS_DISABLED))
        // A newer downloader's unknown refusal is still a refusal, never a silent local download.
        assertEquals(FetchFailure.DOWNLOADER_DISABLED, DownloaderBlocks.failureFor(1999))

        assertEquals(R.string.downloader_blocked_killed, DownloaderBlocks.messageRes(FetchFailure.DOWNLOADER_KILLED))
        assertEquals(R.string.downloader_blocked_maintenance, DownloaderBlocks.messageRes(FetchFailure.DOWNLOADER_MAINTENANCE))
        assertEquals(R.string.downloader_blocked_update, DownloaderBlocks.messageRes(FetchFailure.DOWNLOADER_UPDATE_REQUIRED))
        assertEquals(R.string.downloader_blocked_disabled, DownloaderBlocks.messageRes(FetchFailure.DOWNLOADER_DISABLED))
        assertNull(DownloaderBlocks.messageRes(FetchFailure.NETWORK))
        assertTrue(DownloaderBlocks.isBlock(FetchFailure.DOWNLOADER_MAINTENANCE))
        assertFalse(DownloaderBlocks.isBlock(FetchFailure.DISK_FULL))
    }

    @Test
    fun maintenanceUsesTheRemoteMessageOthersTheDefault() {
        assertEquals("Back at 6", DownloaderBlocks.message(FetchFailure.DOWNLOADER_MAINTENANCE, " Back at 6 ", "default"))
        assertEquals("default", DownloaderBlocks.message(FetchFailure.DOWNLOADER_MAINTENANCE, "  ", "default"))
        assertEquals("default", DownloaderBlocks.message(FetchFailure.DOWNLOADER_KILLED, "ignored", "default"))
    }

    @Test
    fun runningJobsMayFinishNewOnesAreRefused() {
        val admission = TransferAdmission(graceMs = 1_000)
        assertEquals(DownloadBlock.NONE, admission.admit("running", DownloadBlock.NONE, 0))
        // Blocked from here on: the running job keeps going while it keeps asking.
        assertEquals(DownloadBlock.NONE, admission.admit("running", DownloadBlock.MAINTENANCE, 900))
        assertEquals(DownloadBlock.NONE, admission.admit("running", DownloadBlock.MAINTENANCE, 1_800))
        assertEquals(DownloadBlock.MAINTENANCE, admission.admit("new", DownloadBlock.MAINTENANCE, 1_800))
        assertEquals(DownloadBlock.MAINTENANCE, admission.admit(null, DownloadBlock.MAINTENANCE, 1_800))
        assertEquals(DownloadBlock.KILLED, admission.admit(" ", DownloadBlock.KILLED, 1_800))
        // Idle longer than the grace: treated as new work.
        assertEquals(DownloadBlock.KILLED, admission.admit("running", DownloadBlock.KILLED, 3_000))
    }
}
