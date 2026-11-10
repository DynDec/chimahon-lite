package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.hippo.unifile.UniFile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Buffer
import okio.FileSystem
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalCoverReference
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.injectLazy
import java.io.IOException

/** The first page of a local chapter, shown as its cover. [lastModified] renews it when the file changes. */
data class ChapterCover(
    val chapterUrl: String,
    val lastModified: Long,
)

class ChapterCoverKeyer : Keyer<ChapterCover> {
    override fun key(data: ChapterCover, options: Options): String {
        return "chapter-cover;${data.chapterUrl};${data.lastModified}"
    }
}

/**
 * Reads a chapter's cover straight from its file. Nothing is written to storage: only where the cover sits in
 * the archive is remembered, and only until the app closes.
 */
class ChapterCoverFetcher(
    private val data: ChapterCover,
    private val options: Options,
) : Fetcher {

    private val fileSystem: LocalSourceFileSystem by injectLazy()
    private val coverManager: LocalCoverManager by injectLazy()

    override suspend fun fetch(): FetchResult {
        val bytes = archiveReads.withPermit { readCover() }
            ?: throw IOException("No cover found in ${data.chapterUrl}")
        return SourceFetchResult(
            source = ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun readCover(): ByteArray? {
        val key = "${data.chapterUrl}\n${data.lastModified}"
        synchronized(coverLocations) { coverLocations[key] }?.let { known ->
            read(known)?.let { return it }
        }
        val chapter = fileSystem.getChapterFile(data.chapterUrl) ?: return null
        val cover = coverManager.forChapter(chapter) ?: return null
        synchronized(coverLocations) { coverLocations[key] = cover }
        return read(cover)
    }

    private fun read(cover: String): ByteArray? {
        return if (LocalCoverReference.isReference(cover)) {
            coverManager.read(cover)
        } else {
            runCatching {
                UniFile.fromUri(options.context, cover.toUri())?.openInputStream()?.use { it.readBytes() }
            }.getOrNull()
        }
    }

    class Factory : Fetcher.Factory<ChapterCover> {
        override fun create(data: ChapterCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return ChapterCoverFetcher(data, options)
        }
    }

    private companion object {
        /** A shelf of volumes asks for every cover at once; reading that many archives together stalls storage. */
        val archiveReads = Semaphore(3)

        /** Listing an archive to find its cover is the slow part, so the answer is kept for later reads. */
        val coverLocations = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 512
        }
    }
}
