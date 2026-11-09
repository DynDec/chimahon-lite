package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterLayoutTest {

    @Test
    fun `volume names are shown as a grid`() {
        assertTrue(ChapterLayout.looksLikeVolumes(listOf("Series v01", "Series v02", "Series v03")))
        assertTrue(ChapterLayout.looksLikeVolumes(listOf("やがて君になる 第01巻", "やがて君になる 第02巻")))
        assertTrue(ChapterLayout.looksLikeVolumes(listOf("1", "2", "3")))
        assertTrue(ChapterLayout.looksLikeVolumes(listOf("A single book")))
    }

    @Test
    fun `chapter names are shown as a list`() {
        assertFalse(ChapterLayout.looksLikeVolumes(listOf("Chapter 1", "Chapter 2", "Chapter 3")))
        assertFalse(ChapterLayout.looksLikeVolumes(listOf("Series ch.12", "Series Ch 13")))
        assertFalse(ChapterLayout.looksLikeVolumes(listOf("第1話", "第2話", "第十話")))
        assertFalse(ChapterLayout.looksLikeVolumes(listOf("Vol.1 Ch.1", "Vol.1 Ch.2")))
    }

    @Test
    fun `a few chapters among volumes keep the grid`() {
        assertTrue(ChapterLayout.looksLikeVolumes(listOf("v01", "v02", "v03", "Chapter 40")))
    }

    @Test
    fun `long series and empty series are shown as a list`() {
        assertFalse(ChapterLayout.looksLikeVolumes(List(61) { "${it + 1}" }))
        assertFalse(ChapterLayout.looksLikeVolumes(emptyList()))
    }

    @Test
    fun `volume label drops the series title`() {
        assertEquals("第01巻", ChapterLayout.volumeLabel("やがて君になる", "やがて君になる 第01巻"))
        assertEquals("v02", ChapterLayout.volumeLabel("Series", "Series - v02"))
        assertEquals("Series", ChapterLayout.volumeLabel("Series", "Series"))
    }
}
