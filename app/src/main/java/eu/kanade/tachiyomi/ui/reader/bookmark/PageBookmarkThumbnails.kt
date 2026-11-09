package eu.kanade.tachiyomi.ui.reader.bookmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.InputStream

/**
 * Small previews of bookmarked pages, kept in app storage so the bookmark list never has to open
 * the volumes the pages come from.
 */
class PageBookmarkThumbnails(context: Context) {

    private val dir = File(context.filesDir, "page_bookmarks")

    fun file(chapterId: Long, pageIndex: Int): File = File(dir, "${chapterId}_$pageIndex.jpg")

    fun save(chapterId: Long, pageIndex: Int, stream: () -> InputStream) {
        try {
            // Archive page streams ignore the offset and length of partial reads, which overruns
            // BitmapFactory's native buffer when it decodes them directly. Read them whole instead.
            val bytes = stream().use { it.readBytes() }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= THUMBNAIL_WIDTH) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return

            dir.mkdirs()
            file(chapterId, pageIndex).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to save bookmark thumbnail" }
        }
    }

    fun delete(chapterId: Long, pageIndex: Int) {
        file(chapterId, pageIndex).delete()
    }
}

private const val THUMBNAIL_WIDTH = 240
