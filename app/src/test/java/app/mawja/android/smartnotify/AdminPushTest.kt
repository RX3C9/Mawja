package app.mawja.android.smartnotify

import app.mawja.remote.Announcement
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdminPushTest {
    private fun a(
        id: String,
        kind: String = Announcement.KIND_PUSH,
        tier: String? = null,
        locale: String? = null,
        start: Long = 100,
        end: Long? = null,
        active: Boolean = true,
    ) = Announcement(id, "t$id", "b$id", kind, tier, locale, active = active, startAt = start, endAt = end)

    @Test
    fun `only live push announcements for this tier and language are posted`() {
        val all =
            listOf(
                a("p1"),
                a("banner", kind = Announcement.KIND_BANNER),
                a("pro", tier = "pro"),
                a("en", locale = "en"),
                a("later", start = 5000),
                a("over", end = 500),
                a("off", active = false),
            )
        assertThat(AdminPush.pending(all, 1000, "free", "ar", emptySet()).map { it.id }).containsExactly("p1")
        assertThat(AdminPush.pending(all, 1000, "pro", "en-US", emptySet()).map { it.id }).containsExactly("p1", "pro", "en")
    }

    @Test
    fun `a posted id is never posted again and at most three go out per run`() {
        val all = listOf(a("w", start = 0), a("x", start = 1), a("y", start = 2), a("z", start = 3))
        assertThat(AdminPush.pending(all, 1000, "free", "ar", emptySet()).map { it.id }).containsExactly("z", "y", "x").inOrder()
        assertThat(AdminPush.pending(all, 1000, "free", "ar", setOf("z")).map { it.id }).containsExactly("y", "x", "w").inOrder()
        assertThat(AdminPush.pending(all, 1000, "free", "ar", setOf("w", "x", "y", "z"))).isEmpty()
    }

    @Test
    fun `prune forgets deleted announcements`() {
        assertThat(AdminPush.prune(setOf("gone", "x"), listOf(a("x")))).containsExactly("x")
    }
}
