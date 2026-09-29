package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import okio.BufferedSource
import tachiyomi.decoder.ImageDecoder

/** The rendering flag and OCR crop must always describe the same processed image. */
internal data class ReaderBorderCropDecision(val enabled: Boolean, val rect: RectF? = null)

internal object ReaderBorderCropPolicy {
    /** Called off the UI thread, after page splitting/merging/rotation. Never consumes [source]. */
    fun evaluate(source: BufferedSource, enabled: Boolean, isAnimated: Boolean): ReaderBorderCropDecision =
        decide(
            enabled = enabled,
            isAnimated = isAnimated,
            isSparse = { isSparseWhitePage(source) },
            detectCrop = { detectNativeCropRect(source) },
        )

    internal fun decide(
        enabled: Boolean,
        isAnimated: Boolean,
        isSparse: () -> Boolean,
        detectCrop: () -> RectF?,
    ): ReaderBorderCropDecision {
        // Animated images keep their existing rendering path; neither analysis is needed.
        if (!enabled || isAnimated) return ReaderBorderCropDecision(enabled)
        val skipCrop = try {
            isSparse()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (skipCrop) return ReaderBorderCropDecision(false)
        return ReaderBorderCropDecision(true, detectCrop())
    }

    private fun isSparseWhitePage(source: BufferedSource): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sampleSize = 1
        while ((largestDimension.toLong() + sampleSize - 1) / sampleSize > 512) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = source.peek().inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false
        try {
            // A decoder that ignores subsampling must not cause an unbounded pixel allocation.
            if (bitmap.width > 512 || bitmap.height > 512) return false
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return SparseWhitePageDetector.isSparse(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectNativeCropRect(source: BufferedSource): RectF? {
        val decoder = try {
            source.peek().inputStream().use {
                ImageDecoder.newInstance(it, cropBorders = true, displayProfile = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            if (decoder.cropX == 0 && decoder.cropY == 0 &&
                decoder.width == decoder.originalWidth && decoder.height == decoder.originalHeight
            ) {
                return null
            }
            return RectF(
                decoder.cropX.toFloat() / decoder.originalWidth,
                decoder.cropY.toFloat() / decoder.originalHeight,
                (decoder.cropX + decoder.width).toFloat() / decoder.originalWidth,
                (decoder.cropY + decoder.height).toFloat() / decoder.originalHeight,
            )
        } finally {
            decoder.recycle()
        }
    }
}
