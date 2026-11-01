package app.mawja.android.ui.screens.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicMusicDiscoveryTest {
    private val sections = ArabicMusicDiscovery.sections

    @Test
    fun `covers the planned sections`() {
        assertTrue(sections.map { it.id }.containsAll(listOf("tarab", "mahraganat", "khaleeji", "maghrebi", "indie")))
    }

    @Test
    fun `ids, labels and queries are distinct`() {
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        assertEquals(sections.size, sections.map { it.label }.toSet().size)
        assertEquals(sections.size, sections.map { it.query }.toSet().size)
    }

    @Test
    fun `every query is Arabic text`() {
        sections.forEach { section ->
            assertTrue(section.id, section.query.isNotBlank() && section.query.any { it in '\u0600'..'\u06FF' })
        }
    }
}
