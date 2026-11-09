package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
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
 * Finding a cover lists the chapter's archive, so the image is kept in Coil's disk cache and the archive is
 * opened once per chapter rather than every time its manga is opened.
 */
class ChapterCoverFetcher(
    private val data: ChapterCover,
    private val options: Options,
    private val diskCacheKey: String,
    private val imageLoader: ImageLoader,
) : Fetcher {

    private val fileSystem: LocalSourceFileSystem by injectLazy()
    private val coverManager: LocalCoverManager by injectLazy()

    override suspend fun fetch(): FetchResult {
        val diskCache = imageLoader.diskCache
        if (options.diskCachePolicy.readEnabled) {
            diskCache?.openSnapshot(diskCacheKey)?.let { return it.toFetchResult() }
        }

        val bytes = archiveReads.withPermit { readCover() }
            ?: throw IOException("No cover found in ${data.chapterUrl}")

        if (diskCache != null && options.diskCachePolicy.writeEnabled) {
            write(diskCache, bytes)?.let { return it.toFetchResult() }
        }
        return SourceFetchResult(
            source = ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun readCover(): ByteArray? {
        val chapter = fileSystem.getChapterFile(data.chapterUrl) ?: return null
        val cover = coverManager.forChapter(chapter) ?: return null
        return if (LocalCoverReference.isReference(cover)) {
            coverManager.read(cover)
        } else {
            UniFile.fromUri(options.context, cover.toUri())?.openInputStream()?.use { it.readBytes() }
        }
    }

    private fun write(diskCache: DiskCache, bytes: ByteArray): DiskCache.Snapshot? {
        val editor = diskCache.openEditor(diskCacheKey) ?: return null
        return try {
            diskCache.fileSystem.write(editor.data) { write(bytes) }
            editor.commitAndOpenSnapshot()
        } catch (e: Exception) {
            runCatching { editor.abort() }
            null
        }
    }

    private fun DiskCache.Snapshot.toFetchResult(): FetchResult {
        return SourceFetchResult(
            source = ImageSource(
                file = this.data,
                fileSystem = FileSystem.SYSTEM,
                diskCacheKey = diskCacheKey,
                closeable = this,
            ),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ChapterCover> {
        override fun create(data: ChapterCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return ChapterCoverFetcher(
                data = data,
                options = options,
                diskCacheKey = imageLoader.components.key(data, options)!!,
                imageLoader = imageLoader,
            )
        }
    }

    private companion object {
        /** A shelf of volumes asks for every cover at once; reading that many archives together stalls storage. */
        val archiveReads = Semaphore(3)
    }
}
