package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.DiskUtil
import mihon.core.archive.ZipWriter
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"
private const val COVER_ARCHIVE_NAME = "cover.cbi"
private const val LOOSE_COVER_PREFIX = ".chimahon-cover-"

actual class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    actual fun find(mangaUrl: String): UniFile? {
        val mangaDirectory = fileSystem.getMangaDirectory(mangaUrl)
        if (mangaDirectory == null) {
            return findLooseCover(mangaUrl)
        }

        return mangaDirectory.listFiles().orEmpty().asSequence()
            // Get all file whose names start with "cover"
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            // Get the first actual image
            .firstOrNull {
                ImageUtil.isImage(it.name) { it.openInputStream() } || it.name == COVER_ARCHIVE_NAME
            }
    }

    actual fun update(
        manga: SManga,
        inputStream: InputStream,
        // SY -->
        encrypted: Boolean,
        // SY <--
    ): UniFile? {
        val mangaDirectory = fileSystem.getMangaDirectory(manga.url)
        val directory = mangaDirectory ?: getLooseCoverDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        // SY -->
        val targetFile = find(manga.url) ?: directory.createFile(
            if (mangaDirectory != null) {
                if (encrypted) COVER_ARCHIVE_NAME else DEFAULT_COVER_NAME
            } else {
                coverName(manga.url, if (encrypted) COVER_ARCHIVE_NAME else DEFAULT_COVER_NAME)
            },
        )
        // SY <--
        if (targetFile == null) {
            inputStream.close()
            return null
        }

        inputStream.use { input ->
            // SY -->
            if (encrypted) {
                ZipWriter(context, targetFile, encrypt = true).use { writer ->
                    writer.write(inputStream.readBytes(), DEFAULT_COVER_NAME)
                }
                if (mangaDirectory != null) {
                    DiskUtil.createNoMediaFile(directory, context)
                }

                manga.thumbnail_url = targetFile.uri.toString()
                return targetFile
            } else {
                // SY <--
                targetFile.openOutputStream().use { output ->
                    input.copyTo(output)
                }
                if (mangaDirectory != null) {
                    DiskUtil.createNoMediaFile(directory, context)
                }
                manga.thumbnail_url = targetFile.uri.toString()
                return targetFile
            }
        }
    }

    private fun findLooseCover(mangaUrl: String): UniFile? {
        val mangaEntry = fileSystem.getMangaEntry(mangaUrl)?.takeIf { it.isFile } ?: return null
        val directory = getParentDirectory(mangaEntry) ?: return null

        return sequenceOf(
            directory.findFile(coverName(mangaUrl, DEFAULT_COVER_NAME)),
            directory.findFile(coverName(mangaUrl, COVER_ARCHIVE_NAME)),
        )
            .filterNotNull()
            .filter { it.isFile }
            .firstOrNull {
                it.name?.equals(coverName(mangaUrl, COVER_ARCHIVE_NAME), ignoreCase = true) == true ||
                    ImageUtil.isImage(it.name) { it.openInputStream() }
            }
    }

    private fun getLooseCoverDirectory(mangaUrl: String): UniFile? {
        val mangaEntry = fileSystem.getMangaEntry(mangaUrl)?.takeIf { it.isFile } ?: return null
        return getParentDirectory(mangaEntry)
    }

    private fun getParentDirectory(file: UniFile): UniFile? {
        file.parentFile?.takeIf { it.isDirectory }?.let { return it }

        // UniFile.fromUri does not retain the parent. Resolve direct children through the
        // selected source root so loose files remain writable after their URI is persisted.
        return fileSystem.getBaseDirectory()
            ?.takeIf { it.findFile(file.name)?.uri == file.uri }
    }

    private fun coverName(mangaUrl: String, coverName: String): String {
        val extension = coverName.substringAfterLast('.')
        return "$LOOSE_COVER_PREFIX${Hash.sha256(mangaUrl)}.$extension"
    }
}
