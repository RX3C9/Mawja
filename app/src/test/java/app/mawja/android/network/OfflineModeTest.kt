package app.mawja.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeTest {
    @Test
    fun reasonPrefersAirplaneMode() {
        assertEquals(OfflineReason.NONE, OfflineMode.reason(true, true))
        assertEquals(OfflineReason.AIRPLANE_MODE, OfflineMode.reason(false, true))
        assertEquals(OfflineReason.NO_CONNECTION, OfflineMode.reason(false, false))
    }

    @Test
    fun localScreensWorkOffline() {
        listOf("downloads", "settings", "notes", "player/abc", "musicPlayer", "settings/eq").forEach {
            assertTrue(it, OfflineMode.worksOffline(it))
        }
        assertFalse(OfflineMode.worksOffline("home"))
        assertFalse(OfflineMode.worksOffline("search"))
    }

    @Test
    fun noticeOnlyOnNetworkScreens() {
        assertTrue(OfflineMode.shouldNotify(OfflineReason.NO_CONNECTION, "home"))
        assertTrue(OfflineMode.shouldNotify(OfflineReason.AIRPLANE_MODE, "search"))
        assertFalse(OfflineMode.shouldNotify(OfflineReason.NONE, "home"))
        assertFalse(OfflineMode.shouldNotify(OfflineReason.NO_CONNECTION, "downloads"))
    }
}
