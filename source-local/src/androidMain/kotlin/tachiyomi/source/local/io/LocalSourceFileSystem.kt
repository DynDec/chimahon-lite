package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager

actual class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    actual fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
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
