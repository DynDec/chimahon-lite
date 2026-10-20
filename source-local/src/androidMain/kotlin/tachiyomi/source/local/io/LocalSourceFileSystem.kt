package tachiyomi.source.local.io

import android.app.Application
import com.hippo.unifile.ListedDocumentFile
import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy

actual class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    // Chimahon -->
    private val context: Application by injectLazy()
    // Chimahon <--

    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    actual fun getFilesInBaseDirectory(): List<UniFile> {
        val baseDirectory = getBaseDirectory() ?: return emptyList()
        // Chimahon -->
        // Listed documents keep their type and modification time, and list their own folders the same way.
        return (ListedDocumentFile.listFiles(context, baseDirectory) ?: baseDirectory.listFiles()).orEmpty().toList()
        // Chimahon <--
    }

    actual fun getMangaEntry(url: String): UniFile? {
        return resolvePersistedUri(url)
            ?: getBaseDirectory()?.findFile(url)
    }

    actual fun getMangaDirectory(name: String): UniFile? {
        return getMangaEntry(name)
            ?.takeIf { it.isDirectory }
    }

    actual fun getFilesInMangaDirectory(name: String): List<UniFile> {
        val mangaEntry = getMangaEntry(name) ?: return emptyList()
        return if (mangaEntry.isDirectory) {
            mangaEntry.listFiles().orEmpty().toList()
        } else {
            listOf(mangaEntry)
        }
    }

    actual fun getChapterFile(url: String): UniFile? {
        resolvePersistedUri(url)?.let { return it }

        val parts = url.split('/', limit = 2)
        if (parts.size != 2) return null

        return getBaseDirectory()
            ?.findFile(parts[0])
            ?.findFile(parts[1])
    }

    actual fun getChapterFile(mangaUrl: String, chapterUrl: String): UniFile? {
        resolvePersistedUri(chapterUrl)?.let { return it }

        // A missing persisted file must not resolve to different content with the same filename.
        // Relative paths from legacy backups can still use the filename fallback below.
        if (chapterUrl.startsWith("content://") || chapterUrl.startsWith("file://")) return null

        val mangaEntry = getMangaEntry(mangaUrl)
        if (mangaEntry?.isDirectory == true) {
            val chapterName = chapterUrl.substringAfterLast('/')
            mangaEntry.findFile(chapterName)?.let { return it }
        }

        return getChapterFile(chapterUrl)
    }

    actual fun usesDefaultBaseDirectory(): Boolean {
        return storageManager.usesDefaultMangaDirectory()
    }

    private fun resolvePersistedUri(url: String): UniFile? {
        return storageManager.getFileFromUri(url)?.takeIf { it.exists() }
    }
}
