package eu.kanade.domain.manga.interactor

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.storage.areSameFileUris
import tachiyomi.core.common.storage.fileUriKey
import tachiyomi.core.common.storage.isFileUriInside
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

/**
 * Keeps the library in step with the manga folder and the other library folders. Newly listed manga are added, to
 * their folder's category if they are in none, and manga that are no longer listed are taken out and added back
 * when they return. Manga the user removed stay removed. Manga outside every library folder, such as those of a
 * folder chosen before, are left in the library, and so are those of a folder that cannot be read. Database rows
 * and reading history are never deleted.
 */
class SyncLocalLibrary(
    private val mangas: MangaRepository = Injekt.get(),
    private val chapters: ChapterRepository = Injekt.get(),
    private val categories: CategoryRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val storagePreferences: StoragePreferences = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
) {
    /** [unreadable] counts the library folders that could not be read and were left as they were. */
    data class Result(val added: Int, val removed: Int, val unreadable: Int = 0)

    /** [directory] is null when the folder cannot be read. */
    private class Folder(val url: String, val directory: UniFile?, val categoryId: Long, val isMangaFolder: Boolean)

    private class Listed(val manga: SManga, val categoryId: Long)

    /**
     * Null when no library folder can be read, in which case the library is left unchanged. [onApplied] runs once
     * the library has changed, before the chapters of added manga are listed.
     */
    suspend fun await(onApplied: suspend (Result) -> Unit = {}): Result? = mutex.withLock {
        withIOContext { sync(onApplied) }
    }

    private suspend fun sync(onApplied: suspend (Result) -> Unit): Result? {
        val start = System.nanoTime()
        sourceManager.isInitialized.first { it }
        val source = sourceManager.get(LocalSource.ID) as? LocalSource ?: return null
        val folders = libraryFolders()
        // An unreadable folder lists nothing, which must not take its manga out of the library.
        val readable = folders.filter { it.directory != null }
        if (readable.isEmpty()) return null

        // By file key, so a manga listed through two folders, or stored under another spelling, is one manga. A
        // manga listed by more than one folder goes to the category of the first.
        val listed = LinkedHashMap<String, Listed>()
        coroutineScope {
            readable.map { folder ->
                async {
                    val manga = if (folder.isMangaFolder) {
                        source.getPopularManga(1).mangas
                    } else {
                        source.getMangaIn(folder.directory!!)
                    }
                    folder to manga
                }
            }.awaitAll()
        }.forEach { (folder, manga) ->
            manga.forEach { listed.putIfAbsent(fileUriKey(it.url), Listed(it, folder.categoryId)) }
        }
        val listedAt = System.nanoTime()
        val rows = mangas.getMangaBySourceId(LocalSource.ID)
        // A favorite wins over other rows of the same folder.
        val rowsByKey = HashMap<String, Manga>()
        rows.sortedByDescending { it.favorite }.forEach { rowsByKey.putIfAbsent(fileUriKey(it.url), it) }

        val knownPreference = storagePreferences.localLibraryKnownFolders()
        val missingPreference = storagePreferences.localLibraryMissingFolders()
        val firstSync = !knownPreference.isSet()
        val known = knownPreference.get()
        val missing = missingPreference.get()
        val knownKeys = known.mapTo(HashSet(), ::fileUriKey)
        val missingKeys = missing.mapTo(HashSet(), ::fileUriKey)

        val favoriteKeys = HashSet<String>()
        val toRemove = mutableListOf<Manga>()
        for (manga in rows.filter { it.favorite }) {
            val key = listedKeyOf(manga.url, listed)
            when {
                key != null -> favoriteKeys += key
                readable.any { isInFolder(manga.url, it) } -> toRemove += manga
            }
        }

        val toInsert = mutableListOf<SManga>()
        val toAdd = mutableListOf<Manga>()
        for ((key, item) in listed) {
            if (key in favoriteKeys) continue
            val row = rowsByKey[key]
            val add = when {
                key in missingKeys -> true
                // Listed by an earlier sync and not in the library: the user removed it.
                key in knownKeys -> false
                row == null -> true
                // Before the first sync, only a row with chapters can have been in the library.
                firstSync -> chapters.getChapterByMangaId(row.id).isEmpty()
                // Opened from Browse before a sync listed it.
                else -> true
            }
            if (!add) continue
            if (row == null) toInsert += item.manga else toAdd += row
        }
        // Taken out while their folder was a library folder, or when another was chosen before this check existed.
        for (url in missing) {
            val key = fileUriKey(url)
            val row = rowsByKey[key]?.takeIf { !it.favorite && key !in listed } ?: continue
            if (folders.none { isInFolder(url, it) } && storageManager.getFileFromUri(url)?.exists() == true) {
                toAdd += row
            }
        }

        val categoryIds = if (readable.any { it.categoryId != 0L }) {
            categories.getAll().mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }

        // The library and the remembered folders must change together, or a later sync would read a half-applied
        // change as the user removing manga.
        val added = withNonCancellableContext {
            val added = (toAdd + networkToLocalManga(toInsert.map { it.toDomainManga(LocalSource.ID) }))
                .distinctBy { it.id }
            val now = Instant.now().toEpochMilli()
            mangas.updateAll(
                added.map { MangaUpdate(id = it.id, favorite = true, dateAdded = it.dateAdded.takeIf { d -> d > 0 } ?: now) },
            )
            mangas.updateAll(toRemove.map { MangaUpdate(id = it.id, favorite = false) })
            mangas.addUncategorizedToCategories(
                added.mapNotNull { manga ->
                    val categoryId = listed[fileUriKey(manga.url)]?.categoryId?.takeIf { it in categoryIds }
                    categoryId?.let { manga.id to it }
                }.toMap(),
            )
            knownPreference.set(known + listed.values.map { it.manga.url })
            val addedKeys = added.mapTo(HashSet()) { fileUriKey(it.url) }
            missingPreference.set(
                missing.filterTo(HashSet()) { fileUriKey(it).let { key -> key !in listed && key !in addedKeys } } +
                    toRemove.map { it.url },
            )
            added
        }
        val result = Result(added = added.size, removed = toRemove.size, unreadable = folders.size - readable.size)
        logcat(LogPriority.INFO) {
            "Local library sync: listed ${listed.size} from ${readable.size} folders in " +
                "${elapsedMs(start, listedAt)} ms, added ${result.added} and removed ${result.removed} in " +
                "${elapsedMs(start)} ms"
        }
        onApplied(result)

        for (manga in added) {
            val restored = fileUriKey(manga.url) in missingKeys
            if (!restored) setMangaDefaultChapterFlags.await(manga)
            // List chapters now so the library shows unread counts without opening each manga.
            try {
                updateMangaFromRemote(manga.copy(favorite = true), fetchDetails = !restored, fetchChapters = true)
                    .exceptionOrNull()
                    ?.let { logcat(LogPriority.ERROR, it) { "Could not list chapters for ${manga.title}" } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not list chapters for ${manga.title}" }
            }
        }
        if (added.isNotEmpty()) {
            logcat(LogPriority.INFO) { "Local library sync: listed chapters of ${added.size} in ${elapsedMs(start)} ms" }
        }
        return result
    }

    /** The manga folder, then each other library folder that is not the same folder. */
    private fun libraryFolders(): List<Folder> {
        val mangaFolder = fileSystem.getBaseDirectory()
        val folders = mutableListOf(
            Folder(
                url = mangaFolder?.uri?.toString().orEmpty(),
                directory = mangaFolder?.takeIf { it.canRead() },
                categoryId = storagePreferences.mangaDirectoryCategory().get(),
                isMangaFolder = true,
            ),
        )
        for (folder in storagePreferences.libraryFolders().get()) {
            if (folders.any { areSameFileUris(it.url, folder.uri) }) continue
            folders += Folder(
                url = folder.uri,
                directory = storageManager.getFileFromUri(folder.uri)?.takeIf { it.isDirectory && it.canRead() },
                categoryId = folder.categoryId,
                isMangaFolder = false,
            )
        }
        return folders
    }

    private fun elapsedMs(start: Long, end: Long = System.nanoTime()) = (end - start) / 1_000_000

    /** Whether [url] is a manga below [folder]. Legacy folder names are relative to the manga folder. */
    private fun isInFolder(url: String, folder: Folder): Boolean {
        if (!url.startsWith("content://") && !url.startsWith("file://")) return folder.isMangaFolder
        return isFileUriInside(url, folder.url)
    }

    /** The listed key [url] refers to, also for document URIs spelled differently and legacy folder names. */
    private fun listedKeyOf(url: String, listed: Map<String, Listed>): String? {
        val key = fileUriKey(url)
        if (key in listed) return key
        if (url.startsWith("content://") || url.startsWith("file://")) return null
        return listed.entries
            .firstOrNull { it.value.manga.title == url && fileSystem.getMangaEntry(url) != null }
            ?.key
    }

    private companion object {
        val mutex = Mutex()
    }
}
