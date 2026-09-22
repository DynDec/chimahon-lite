package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.Hash
import mihon.core.archive.ZipWriter
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val DEFAULT_COVER_NAME = "cover.jpg"
private const val COVER_ARCHIVE_NAME = "cover.cbi"
private const val GENERATED_COVER_DIRECTORY = "local_covers"
private const val GENERATED_COVER_PREFIX = "cover-"
private const val LEGACY_LOOSE_COVER_PREFIX = ".chimahon-cover-"

actual class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    actual fun find(mangaUrl: String): UniFile? {
        // Generated covers live in the app cache so scanning a source never writes to
        // user storage. Keep source folder covers as a fallback for existing libraries.
        findGeneratedCover(mangaUrl)?.let { return it }

        val mangaDirectory = fileSystem.getMangaDirectory(mangaUrl) ?: return null
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
        val target = generatedCoverFile(manga.url, if (encrypted) COVER_ARCHIVE_NAME else DEFAULT_COVER_NAME)
        val targetParent = target.parentFile
        if (targetParent == null || !targetParent.exists() && !targetParent.mkdirs()) {
            inputStream.close()
            return null
        }

        val temporary = File.createTempFile("${target.name}.", ".tmp", targetParent)
        try {
            val temporaryFile = UniFile.fromFile(temporary)
            if (temporaryFile == null) {
                inputStream.close()
                return null
            }

            // SY -->
            if (encrypted) {
                ZipWriter(context, temporaryFile, encrypt = true).use { writer ->
                    writer.write(inputStream.use { it.readBytes() }, DEFAULT_COVER_NAME)
                }
            } else {
                // SY <--
                inputStream.use { input ->
                    temporaryFile.openOutputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            // A chapter can change format between refreshes. Remove the old representation
            // so find() cannot return a stale encrypted cover after a normal image is written.
            generatedCoverFile(
                manga.url,
                if (encrypted) DEFAULT_COVER_NAME else COVER_ARCHIVE_NAME,
            ).takeUnless { it == target }?.delete()
            deleteLegacyLooseCovers(manga.url)

            val targetFile = UniFile.fromFile(target) ?: return null
            manga.thumbnail_url = targetFile.uri.toString()
            return targetFile
        } finally {
            temporary.delete()
        }
    }

    private fun findGeneratedCover(mangaUrl: String): UniFile? {
        return sequenceOf(
            generatedCoverFile(mangaUrl, DEFAULT_COVER_NAME),
            generatedCoverFile(mangaUrl, COVER_ARCHIVE_NAME),
        )
            .filter { it.isFile }
            .firstOrNull {
                it.extension.equals(COVER_ARCHIVE_NAME.substringAfterLast('.'), ignoreCase = true) ||
                    ImageUtil.isImage(it.name) { it.inputStream() }
            }
            ?.let { UniFile.fromFile(it) }
    }

    private fun generatedCoverFile(mangaUrl: String, coverName: String): File {
        val directory = File(context.cacheDir, GENERATED_COVER_DIRECTORY).also { it.mkdirs() }
        val extension = coverName.substringAfterLast('.')
        return File(directory, "$GENERATED_COVER_PREFIX${Hash.sha256(mangaUrl)}.$extension")
    }

    private fun deleteLegacyLooseCovers(mangaUrl: String) {
        if (fileSystem.getMangaDirectory(mangaUrl) != null) return

        val mangaEntry = fileSystem.getMangaEntry(mangaUrl)?.takeIf { it.isFile } ?: return
        val directory = mangaEntry.parentFile?.takeIf { it.isDirectory }
            ?: fileSystem.getBaseDirectory()?.takeIf { it.findFile(mangaEntry.name)?.uri == mangaEntry.uri }
            ?: return
        val hash = Hash.sha256(mangaUrl)
        sequenceOf(DEFAULT_COVER_NAME, COVER_ARCHIVE_NAME)
            .map { "$LEGACY_LOOSE_COVER_PREFIX$hash.${it.substringAfterLast('.')}" }
            .forEach { directory.findFile(it)?.delete() }
    }
}
