package tachiyomi.source.local.image

import com.hippo.unifile.UniFile

/**
 * Local covers are never copied. A manga's cover is either an image in its folder or a reference to the
 * first image of its first chapter, which is read on demand when the cover is displayed.
 */
expect class LocalCoverManager {

    /** A cover image the user placed in the manga folder, such as `cover.jpg`. */
    fun find(mangaUrl: String): UniFile?

    /** A thumbnail URL for [chapter]'s first image, without reading the image itself. */
    fun forChapter(chapter: UniFile): String?

    /** Whether [thumbnailUrl] already points into [chapter]. */
    fun belongsTo(thumbnailUrl: String?, chapter: UniFile): Boolean

    /** Reads the image behind an archive cover reference, or `null` if it no longer exists. */
    fun read(thumbnailUrl: String): ByteArray?
}
