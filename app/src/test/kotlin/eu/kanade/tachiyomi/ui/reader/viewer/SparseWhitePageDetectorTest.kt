package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SparseWhitePageDetectorTest {
    @Test
    fun `blank white and transparent pages are sparse`() {
        assertTrue(isSparse(page()))
        assertTrue(isSparse(page(0x00000000)))
        assertTrue(isSparse(page(0x10ffffff)))
    }

    @Test
    fun `small horizontal and vertical text blocks are sparse regardless of position`() {
        for ((left, top, width, height) in listOf(
            listOf(10, 50, 30, 8),
            listOf(50, 10, 8, 30),
            listOf(0, 0, 30, 8),
            listOf(70, 92, 30, 8),
        )) {
            assertTrue(isSparse(page().apply { fillRect(left, top, width, height) }))
        }
    }

    @Test
    fun `dense compact logos and doodles are sparse`() {
        // A solid title logo in a corner: 6% ink, all of it inside its own bounds.
        assertTrue(isSparse(page().apply { fillRect(2, 80, 40, 15) }))
        // A doodle filling exactly a quarter of the page.
        assertTrue(isSparse(page().apply { fillRect(50, 50, 50, 50) }))
    }

    @Test
    fun `one quarter bounding box is included but any larger is excluded`() {
        val pixels = page().apply {
            this[0] = BLACK
            this[49 * 100 + 49] = BLACK
        }
        assertTrue(isSparse(pixels))
        pixels[50 * 100 + 49] = BLACK
        assertFalse(isSparse(pixels))
    }

    @Test
    fun `white threshold checks every channel`() {
        assertTrue(isSparse(page(0xffebebeb.toInt())))
        for (color in listOf(0xffeaebeb, 0xffebeaeb, 0xffebebea)) {
            assertFalse(isSparse(page(color.toInt())))
        }
    }

    @Test
    fun `alpha is composited over white before classification`() {
        assertTrue(isSparse(page(0x14000000))) // Composites to RGB 235.
        assertFalse(isSparse(page(0x15000000))) // Composites to RGB 234.
    }

    @Test
    fun `panel scans and dark backgrounds retain cropping`() {
        assertFalse(isSparse(page().apply { fillRect(5, 5, 90, 90) }))
        assertFalse(isSparse(page(BLACK)))
        assertFalse(isSparse(page(0xff303030.toInt())))
    }

    @Test
    fun `pale artwork and widely spaced text retain cropping`() {
        assertFalse(isSparse(page().apply { fillRect(10, 10, 80, 80, 0xffe0e0e0.toInt()) }))
        // Very little ink, but spread across the page rather than a compact transition label.
        assertFalse(
            isSparse(
                page().apply {
                    fillRect(10, 10, 20, 2)
                    fillRect(70, 85, 20, 2)
                },
            ),
        )
    }

    private fun page(color: Int = -1) = IntArray(100 * 100) { color }

    private fun isSparse(pixels: IntArray) = SparseWhitePageDetector.isSparse(pixels, 100, 100)

    private fun IntArray.fillRect(left: Int, top: Int, width: Int, height: Int, color: Int = BLACK) {
        for (y in top until top + height) {
            for (x in left until left + width) this[y * 100 + x] = color
        }
    }

    private companion object {
        const val BLACK = 0xff000000.toInt()
    }
}
