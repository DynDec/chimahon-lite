package tachiyomi.source.local.image

import mihon.core.archive.ArchiveEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalCoverSelectionTest {
    @Test
    fun `a cover with an unrecognized extension is sniffed in order instead of skipped`() {
        // Layout of a converted CBZ: the real cover is .jpeg, which names alone do not recognize.
        val entries = listOf("p_01.png", "p_02.png", "ComicInfo.xml", "cover_00.jpeg").map(::file)
        val sniffed = mutableListOf<String>()

        val cover = LocalCoverSelection.firstImage(entries) { name ->
            name.endsWith(".png") || (name !in KNOWN && name.also { sniffed += it }.endsWith(".jpeg"))
        }

        assertEquals("cover_00.jpeg", cover)
        assertEquals(listOf("ComicInfo.xml", "cover_00.jpeg"), sniffed)
    }

    @Test
    fun `directories are ignored and pages sort naturally`() {
        val entries = listOf(file("10.jpg"), ArchiveEntry("1/", isFile = false, isEncrypted = false), file("2.jpg"))

        assertEquals("2.jpg", LocalCoverSelection.firstImage(entries) { it.endsWith(".jpg") })
        assertNull(LocalCoverSelection.firstImage(listOf(file("notes.txt"))) { false })
    }

    @Test
    fun `references from the earlier selection are outdated`() {
        val earlier = "chimahon-cover://archive?file=content%3A%2F%2Fbook.cbz&entry=p_01.png"
        val current = "$earlier&v=2"

        assertTrue(LocalCoverReference.isOutdated(earlier))
        assertFalse(LocalCoverReference.isOutdated(current))
        assertTrue(LocalCoverReference.isOutdated("$earlier&v=1"))
        assertFalse(LocalCoverReference.isOutdated("content://tree/document/cover.jpg"))
    }

    private fun file(name: String) = ArchiveEntry(name, isFile = true, isEncrypted = false)

    private companion object {
        val KNOWN = setOf("p_01.png", "p_02.png")
    }
}
