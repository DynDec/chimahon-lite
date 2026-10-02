package eu.kanade.domain.manga.interactor

import chimahon.ocr.OcrCacheManager
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.isActionable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.storage.areSameFileUris
import tachiyomi.core.common.storage.isSameFileAs
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.withRelinkRetention
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/** Relink locations in one transaction; never delete manga, chapters, or their history. */
class RelinkLocalManga(
    private val mangas: MangaRepository = Injekt.get(),
    private val chapters: ChapterRepository = Injekt.get(),
    private val handler: DatabaseHandler = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val covers: LocalCoverManager = Injekt.get(),
    private val ocrCache: OcrCacheManager = Injekt.get(),
    private val ocrManager: OcrManager = Injekt.get(),
) {
    enum class Error { NotLocal, Unavailable, SameFolder, AlreadyLinked, EmptyFolder, OcrBusy, Changed, ConfirmationRequired }
    class RelinkException(val reason: Error) : Exception(reason.name)

    class Preview internal constructor(
        val manga: Manga,
        val folderUrl: String,
        val folderName: String,
        internal val existing: List<Chapter>,
        internal val destination: List<Chapter>,
        internal val plan: LocalFolderRelinkPlanner.Plan,
    ) {
        val matchedCount: Int get() = plan.matches.size
        val retainedCount: Int get() = plan.retained.size
        val addedCount: Int get() = plan.added.size
        val unverifiedCount: Int get() = plan.matches.count { it.needsConfirmation }
        val matchedNames: List<String> get() = plan.matches.map { LocalFolderRelinkPlanner.filename(it.file.url) }
        val retainedNames: List<String> get() = existing.filter { it.id in plan.retained }.map { LocalFolderRelinkPlanner.filename(it.url) }
        val addedNames: List<String> get() = plan.added.map { LocalFolderRelinkPlanner.filename(it.url) }
    }

    suspend fun preview(mangaId: Long, folderUrl: String): Preview = withIOContext {
        val manga = mangas.getMangaById(mangaId)
        if (manga.source != LocalSource.ID) fail(Error.NotLocal)
        checkOcr(mangaId)
        val directory = fileSystem.getMangaDirectory(folderUrl)?.takeIf { it.canRead() } ?: fail(Error.Unavailable)
        val original = fileSystem.getMangaDirectory(manga.url)
        if (areSameFileUris(manga.url, folderUrl) || original?.isSameFileAs(directory) == true) fail(Error.SameFolder)
        checkDestination(manga, directory)
        val source = sourceManager.get(LocalSource.ID) as? LocalSource ?: fail(Error.Unavailable)
        val existing = chapters.getChapterByMangaId(mangaId)
        val update = source.getMangaUpdate(manga.copy(url = folderUrl).toSManga(), emptyList(), false, true)
        val destination = update.chapters.mapIndexed { index, chapter ->
            Chapter.create().copyFromSChapter(chapter).copy(mangaId = mangaId, sourceOrder = index.toLong())
        }
        if (destination.isEmpty()) fail(Error.EmptyFolder)
        val oldFiles = existing.map { chapter ->
            val file = fileSystem.getChapterFile(manga.url, chapter.url)
            val fingerprint = try {
                file?.let { fingerprint(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null // Unreadable old files require the user's explicit unchanged-content confirmation.
            }
            LocalFolderRelinkPlanner.Existing(chapter.id, LocalFolderRelinkPlanner.File(chapter.url, fingerprint))
        }
        val newFiles = destination.map { chapter ->
            val file = fileSystem.getChapterFile(folderUrl, chapter.url) ?: fail(Error.Unavailable)
            LocalFolderRelinkPlanner.File(chapter.url, fingerprint(file))
        }
        val plan = LocalFolderRelinkPlanner.plan(oldFiles, newFiles)
        // Retained legacy paths must remain anchored to the original folder.
        if (existing.any { it.id in plan.retained && !it.url.isAbsoluteFileUrl() && fileSystem.getChapterFile(manga.url, it.url) == null }) {
            fail(Error.Unavailable)
        }
        Preview(manga, folderUrl, directory.name.orEmpty(), existing, destination, plan)
    }

    suspend fun apply(preview: Preview, confirmUnchanged: Boolean) = withIOContext {
        if (preview.unverifiedCount > 0 && !confirmUnchanged) fail(Error.ConfirmationRequired)
        // Recheck content and identities after the preview, before changing any database records.
        val fresh = this@RelinkLocalManga.preview(preview.manga.id, preview.folderUrl)
        if (fresh.manga.url != preview.manga.url || fresh.plan != preview.plan ||
            fresh.existing.map { it.id to it.url } != preview.existing.map { it.id to it.url }
        ) {
            fail(Error.Changed)
        }
        val source = sourceManager.get(LocalSource.ID) ?: fail(Error.Unavailable)
        val copiedCover = covers.copyForRelink(fresh.manga.url, fresh.folderUrl)
        val oldCover = covers.find(fresh.manga.url)
        fresh.plan.matches.forEach { match ->
            ocrCache.preserveForRelink(fresh.manga, fresh.existing.single { it.id == match.id }, source)
        }
        val retainedLegacyUrls = fresh.existing.filter { it.id in fresh.plan.retained && !it.url.isAbsoluteFileUrl() }
            .associate { chapter ->
                chapter.id to (fileSystem.getChapterFile(fresh.manga.url, chapter.url)?.uri?.toString() ?: fail(Error.Unavailable))
            }
        checkOcr(fresh.manga.id)
        handler.await(inTransaction = true) {
            val current = mangas.getMangaById(fresh.manga.id)
            if (current.url != fresh.manga.url) fail(Error.Changed)
            checkDestination(current, fileSystem.getMangaDirectory(fresh.folderUrl) ?: fail(Error.Unavailable))
            val currentChapters = chapters.getChapterByMangaId(current.id)
            if (currentChapters.map { it.id to it.url } != fresh.existing.map { it.id to it.url }) fail(Error.Changed)
            val matches = fresh.plan.matches.associateBy { it.id }
            chapters.updateAll(
                currentChapters.map { chapter ->
                    ChapterUpdate(
                        id = chapter.id,
                        url = matches[chapter.id]?.file?.url ?: retainedLegacyUrls[chapter.id],
                        memo = chapter.memo.withRelinkRetention(true),
                    )
                },
            )
            val newUrls = fresh.plan.added.map { it.url }.toSet()
            val added = fresh.destination.filter { it.url in newUrls }.map { it.copy(dateFetch = System.currentTimeMillis()) }
            check(chapters.addAll(added).size == added.size)
            check(
                mangas.update(
                    MangaUpdate(
                        id = current.id,
                        url = fresh.folderUrl,
                        thumbnailUrl = copiedCover?.uri?.toString().takeIf { current.ogThumbnailUrl == oldCover?.uri?.toString() },
                    ),
                ),
            )
        }
    }

    private fun checkOcr(mangaId: Long) {
        if (ocrManager.queueState.value.any { it.manga.id == mangaId && it.status.isActionable() }) fail(Error.OcrBusy)
    }

    private suspend fun checkDestination(manga: Manga, directory: UniFile) {
        if (mangas.getMangaBySourceId(LocalSource.ID).any { other ->
                other.id != manga.id && (
                    areSameFileUris(other.url, directory.uri.toString()) ||
                        fileSystem.getMangaDirectory(other.url)?.isSameFileAs(directory) == true
                    )
            }
        ) {
            fail(Error.AlreadyLinked)
        }
    }

    private suspend fun fingerprint(file: UniFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        suspend fun read(entry: UniFile) {
            currentCoroutineContext().ensureActive()
            if (entry.isDirectory) {
                val children = entry.listFiles() ?: fail(Error.Unavailable)
                children.filterNot { it.name == ".ocr_cache.json" || it.name.orEmpty().endsWith(".ocr.json") }
                    .sortedBy { it.name }.forEach { child ->
                        digest.update(child.name.orEmpty().toByteArray(Charsets.UTF_8))
                        digest.update(0.toByte())
                        read(child)
                    }
            } else {
                entry.openInputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        digest.update(if (file.isDirectory) 1.toByte() else 0.toByte())
        read(file)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(error: Error): Nothing = throw RelinkException(error)

    private fun String.isAbsoluteFileUrl(): Boolean = startsWith("content://") || startsWith("file://")
}
