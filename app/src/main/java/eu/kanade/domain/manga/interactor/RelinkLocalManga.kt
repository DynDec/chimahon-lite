package eu.kanade.domain.manga.interactor

import chimahon.ocr.OcrCacheManager
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.LocalCoverResolver
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.isActionable
import eu.kanade.tachiyomi.util.lang.Hash
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
import tachiyomi.source.local.image.LocalCoverReference
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
    private val coverResolver: LocalCoverResolver = LocalCoverResolver(),
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
        internal val cover: String?,
        internal val fingerprints: Map<String, Fingerprint>,
    ) {
        val matchedCount: Int get() = plan.matches.size
        val retainedCount: Int get() = plan.retained.size
        val addedCount: Int get() = plan.added.size
        val unverifiedCount: Int get() = plan.matches.count { it.needsConfirmation }
        val matchedNames: List<String> get() = plan.matches.map { LocalFolderRelinkPlanner.filename(it.file.url) }
        val retainedNames: List<String> get() = existing.filter { it.id in plan.retained }.map { LocalFolderRelinkPlanner.filename(it.url) }
        val addedNames: List<String> get() = plan.added.map { LocalFolderRelinkPlanner.filename(it.url) }
    }

    /** Content fingerprint of a chapter file, reusable while its size and modification time are unchanged. */
    internal data class Fingerprint(val length: Long, val lastModified: Long, val value: String)

    suspend fun preview(mangaId: Long, folderUrl: String): Preview = preview(mangaId, folderUrl, emptyMap())

    private suspend fun preview(mangaId: Long, folderUrl: String, known: Map<String, Fingerprint>): Preview = withIOContext {
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
        val newFileByUrl = destination.associate {
            it.url to (fileSystem.getChapterFile(folderUrl, it.url) ?: fail(Error.Unavailable))
        }
        // Only files with the same unique name on both sides can keep their records, so only they are read.
        val candidates = LocalFolderRelinkPlanner.candidateNames(existing.map { it.url }, destination.map { it.url })
        val oldByName = existing.map { it.url }
            .filter { LocalFolderRelinkPlanner.filename(it) in candidates }
            .associate { LocalFolderRelinkPlanner.filename(it) to fileSystem.getChapterFile(manga.url, it) }
        val newByName = newFileByUrl.filterKeys { LocalFolderRelinkPlanner.filename(it) in candidates }
            .mapKeys { LocalFolderRelinkPlanner.filename(it.key) }
        val fingerprints = mutableMapOf<String, Fingerprint>()
        suspend fun fingerprintOf(file: UniFile, counterpart: UniFile?): String {
            // Different sizes already prove different content, so neither file is read.
            if (!file.isDirectory && counterpart != null && !counterpart.isDirectory) {
                val length = file.length()
                if (length != counterpart.length()) return "length:$length"
            }
            return reusableFingerprint(file, known)?.also { fingerprints[file.uri.toString()] = it }?.value
                ?: fingerprint(file)
        }
        val oldFiles = existing.map { chapter ->
            val name = LocalFolderRelinkPlanner.filename(chapter.url)
            val fingerprint = try {
                oldByName[name]?.let { fingerprintOf(it, newByName[name]) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null // Unreadable old files require the user's explicit unchanged-content confirmation.
            }
            LocalFolderRelinkPlanner.Existing(chapter.id, LocalFolderRelinkPlanner.File(chapter.url, fingerprint))
        }
        val newFiles = destination.map { chapter ->
            val name = LocalFolderRelinkPlanner.filename(chapter.url)
            LocalFolderRelinkPlanner.File(chapter.url, newByName[name]?.let { fingerprintOf(it, oldByName[name]) })
        }
        val plan = LocalFolderRelinkPlanner.plan(oldFiles, newFiles)
        // Retained legacy paths must remain anchored to the original folder.
        if (existing.any { it.id in plan.retained && !it.url.isAbsoluteFileUrl() && fileSystem.getChapterFile(manga.url, it.url) == null }) {
            fail(Error.Unavailable)
        }
        Preview(manga, folderUrl, directory.name.orEmpty(), existing, destination, plan, update.manga.thumbnail_url, fingerprints)
    }

    suspend fun apply(preview: Preview, confirmUnchanged: Boolean) = withIOContext {
        if (preview.unverifiedCount > 0 && !confirmUnchanged) fail(Error.ConfirmationRequired)
        // Recheck content and identities after the preview, before changing any database records.
        // Files whose size and modification time are unchanged since the preview are not read again.
        val fresh = this@RelinkLocalManga.preview(preview.manga.id, preview.folderUrl, preview.fingerprints)
        if (fresh.manga.url != preview.manga.url || fresh.plan != preview.plan ||
            fresh.existing.map { it.id to it.url } != preview.existing.map { it.id to it.url }
        ) {
            fail(Error.Changed)
        }
        val source = sourceManager.get(LocalSource.ID) ?: fail(Error.Unavailable)
        // Keep a cover the user picked in an older version before the manga stops referencing it.
        coverResolver.retireLegacyCover(fresh.manga, fresh.cover)
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
                        thumbnailUrl = fresh.cover.takeIf { current.ogThumbnailUrl.isAutomaticLocalCover() },
                    ),
                ),
            )
        }
    }

    private fun checkOcr(mangaId: Long) {
        if (ocrManager.queueState.value.any { it.manga.id == mangaId && it.status.isActionable() }) fail(Error.OcrBusy)
    }

    private suspend fun checkDestination(manga: Manga, directory: UniFile) {
        val target = directory.uri.toString()
        // Resolving another entry only adds a file path comparison, which needs a path on this side.
        // A content URI resolves to itself, so without a path the URI comparison already decides.
        val comparePaths = directory.filePath != null
        if (mangas.getMangaBySourceId(LocalSource.ID).any { other ->
                other.id != manga.id && (
                    areSameFileUris(other.url, target) ||
                        (
                            (comparePaths || !other.url.startsWith("content://")) &&
                                fileSystem.getMangaDirectory(other.url)?.isSameFileAs(directory) == true
                            )
                    )
            }
        ) {
            fail(Error.AlreadyLinked)
        }
    }

    /** A file fingerprint, reused from [known] while the file's size and modification time are unchanged. */
    private suspend fun reusableFingerprint(file: UniFile, known: Map<String, Fingerprint>): Fingerprint? {
        if (file.isDirectory) return null // Directory timestamps do not change when a page is edited.
        val length = file.length()
        val lastModified = file.lastModified()
        known[file.uri.toString()]?.takeIf { it.length == length && it.lastModified == lastModified }?.let { return it }
        return Fingerprint(length, lastModified, fingerprint(file))
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
        return Hash.encodeHex(digest.digest())
    }

    private fun fail(error: Error): Nothing = throw RelinkException(error)

    private fun String.isAbsoluteFileUrl(): Boolean = startsWith("content://") || startsWith("file://")

    /** Covers the app derived from the old folder, as opposed to a URL the user entered. */
    private fun String?.isAutomaticLocalCover(): Boolean =
        this == null || LocalCoverReference.isReference(this) || isAbsoluteFileUrl()
}
