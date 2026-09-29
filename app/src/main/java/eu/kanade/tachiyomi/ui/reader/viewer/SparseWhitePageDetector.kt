package eu.kanade.tachiyomi.ui.reader.viewer

/** Conservative, language-independent detection of mostly empty chapter-transition pages. */
internal object SparseWhitePageDetector {
    fun isSparse(pixels: IntArray, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)

        var foregroundCount = 0
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

                foregroundCount++
                // More than 3% foreground cannot be a sparse white page.
                if (foregroundCount.toLong() * 100 > pixels.size.toLong() * 3) return false
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }

        if (foregroundCount == 0) return true
        val boundsArea = (right - left + 1).toLong() * (bottom - top + 1)
        return boundsArea * 4 <= pixels.size
    }

    private fun compositeOnWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255
}
