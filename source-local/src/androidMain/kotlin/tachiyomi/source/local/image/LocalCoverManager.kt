package tachiyomi.source.local.image

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.archiveEntries
import mihon.core.archive.epubReader
import mihon.core.archive.readArchiveEntry
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.FileNotFoundException

private const val COVER_ARCHIVE_NAME = "cover.cbi"

actual class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    actual fun find(mangaUrl: String): UniFile? =
        fileSystem.getMangaDirectory(mangaUrl)?.listFiles()?.let { findIn(it.toList()) }

    actual fun findIn(files: List<UniFile>): UniFile? {
        return files.asSequence()
            // Get all file whose names start with "cover"
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            // Get the first actual image
            .firstOrNull {
                ImageUtil.isImage(it.name) { it.openInputStream() } || it.name == COVER_ARCHIVE_NAME
            }
    }

    actual fun forChapter(chapter: UniFile): String? = when (val format = Format.valueOf(chapter)) {
        is Format.Directory -> format.file.listFiles().orEmpty()
            .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
            .firstOrNull { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.uri?.toString()
        is Format.Epub -> format.file.epubReader(context).use { it.getCoverImage() }
            ?.let { LocalCoverReference.create(chapter.uri.toString(), it) }
        is Format.Archive -> LocalCoverSelection.firstImage(format.file.archiveEntries(context)) { name ->
            ImageUtil.isImage(name) {
                format.file.readArchiveEntry(context, name)?.inputStream() ?: ByteArray(0).inputStream()
            }
        }?.let { LocalCoverReference.create(chapter.uri.toString(), it) }
    }

    actual fun belongsTo(thumbnailUrl: String?, chapter: UniFile): Boolean {
        if (thumbnailUrl.isNullOrEmpty()) return false
        val chapterUri = chapter.uri.toString()
        LocalCoverReference.parse(thumbnailUrl)?.let {
            return it.fileUri == chapterUri && !LocalCoverReference.isOutdated(thumbnailUrl)
        }
        // Directory chapters point at a child document or file inside the chapter.
        val rest = thumbnailUrl.removePrefix(chapterUri.trimEnd('/'))
        return chapter.isDirectory && rest != thumbnailUrl && (rest.startsWith("/") || rest.startsWith("%2F", ignoreCase = true))
    }

    actual fun read(thumbnailUrl: String): ByteArray? {
        val target = LocalCoverReference.parse(thumbnailUrl) ?: return null
        val file = UniFile.fromUri(context, Uri.parse(target.fileUri)) ?: return null
        return try {
            file.readArchiveEntry(context, target.entry)
        } catch (_: FileNotFoundException) {
            null // Moved or deleted since the reference was resolved.
        }
    }
}
