package eu.kanade.tachiyomi.ui.reader.viewer

/**
 * Conservative, language-independent detection of mostly empty chapter-transition pages.
 *
 * A page is sparse when all its non-white content fits in a quarter of the page. Ink density is
 * deliberately not considered: bold title logos and small doodles on transition pages are dense
 * within their bounds, yet cropping to them would discard the white page around them.
 */
internal object SparseWhitePageDetector {
    fun isSparse(pixels: IntArray, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val alpha = pixel ushr 24
                val red = compositeOnWhite((pixel ushr 16) and 0xff, alpha)
                val green = compositeOnWhite((pixel ushr 8) and 0xff, alpha)
                val blue = compositeOnWhite(pixel and 0xff, alpha)
                if (red >= 235 && green >= 235 && blue >= 235) continue
                if (x in left..right && y in top..bottom) continue

                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
                // The bounds only grow, so a page is rejected as soon as they exceed a quarter.
                val boundsArea = (right - left + 1).toLong() * (bottom - top + 1)
                if (boundsArea * 4 > pixels.size) return false
            }
        }
        return true
    }

    private fun compositeOnWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255
}
