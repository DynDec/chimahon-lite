package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.storage.areSameFileUris
import tachiyomi.core.common.storage.isFileUriInside
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
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
 * Keeps the library in step with the local manga folder. Newly listed folders are added, and folders that are no
 * longer listed are taken out and added back when they return. Manga the user removed stay removed. Manga from
 * other manga folders, such as one chosen before, are left in the library. Database rows and reading history are
 * never deleted.
 */
class SyncLocalLibrary(
    private val mangas: MangaRepository = Injekt.get(),
    private val chapters: ChapterRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val storagePreferences: StoragePreferences = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
) {
    data class Result(val added: Int, val removed: Int)

    /**
     * Null when the manga folder cannot be read, in which case the library is left unchanged. [onApplied] runs once
     * the library has changed, before the chapters of added manga are listed.
     */
    suspend fun await(onApplied: suspend (Result) -> Unit = {}): Result? = mutex.withLock {
        withIOContext { sync(onApplied) }
    }

    private suspend fun sync(onApplied: suspend (Result) -> Unit): Result? {
        val start = System.nanoTime()
        sourceManager.isInitialized.first { it }
        val source = sourceManager.get(LocalSource.ID) as? LocalSource ?: return null
        // An unreadable folder lists nothing, which must not empty the library.
        val baseDirectory = fileSystem.getBaseDirectory()?.takeIf { it.canRead() } ?: return null
        val baseUrl = baseDirectory.uri.toString()

        val listed = source.getPopularManga(1).mangas.associateBy { it.url }
        val listedAt = System.nanoTime()
        val rows = mangas.getMangaBySourceId(LocalSource.ID)
        val rowsByUrl = rows.associateBy { it.url }

        val knownPreference = storagePreferences.localLibraryKnownFolders()
        val missingPreference = storagePreferences.localLibraryMissingFolders()
        val firstSync = !knownPreference.isSet()
        val known = knownPreference.get()
        val missing = missingPreference.get()

        val favoriteUrls = HashSet<String>()
        val toRemove = mutableListOf<Manga>()
        for (manga in rows.filter { it.favorite }) {
            val url = listedUrlOf(manga.url, listed)
            when {
                url != null -> favoriteUrls += url
                isInFolder(manga.url, baseUrl) -> toRemove += manga
            }
        }

        val toInsert = mutableListOf<SManga>()
        val toAdd = mutableListOf<Manga>()
        for ((url, manga) in listed) {
            if (url in favoriteUrls) continue
            val row = rowsByUrl[url]
            val add = when {
                url in missing -> true
                // Listed by an earlier sync and not in the library: the user removed it.
                url in known -> false
                row == null -> true
                // Before the first sync, only a row with chapters can have been in the library.
                firstSync -> chapters.getChapterByMangaId(row.id).isEmpty()
                // Opened from Browse before a sync listed it.
                else -> true
            }
            if (!add) continue
            if (row == null) toInsert += manga else toAdd += row
        }
        // Taken out while their manga folder was chosen, or when another was chosen before this check existed.
        for (url in missing) {
            val row = rowsByUrl[url]?.takeIf { !it.favorite && it.url !in listed } ?: continue
            if (!isInFolder(url, baseUrl) && storageManager.getFileFromUri(url)?.exists() == true) toAdd += row
        }

        // The library and the remembered folders must change together, or a later sync would read a half-applied
        // change as the user removing manga.
        val added = withNonCancellableContext {
            val added = toAdd + networkToLocalManga(toInsert.map { it.toDomainManga(LocalSource.ID) })
            val now = Instant.now().toEpochMilli()
            mangas.updateAll(
                added.map { MangaUpdate(id = it.id, favorite = true, dateAdded = it.dateAdded.takeIf { d -> d > 0 } ?: now) },
            )
            mangas.updateAll(toRemove.map { MangaUpdate(id = it.id, favorite = false) })
            knownPreference.set(known + listed.keys)
            missingPreference.set(missing - listed.keys - added.map { it.url }.toSet() + toRemove.map { it.url })
            added
        }
        val result = Result(added = added.size, removed = toRemove.size)
        logcat(LogPriority.INFO) {
            "Local library sync: listed ${listed.size} in ${elapsedMs(start, listedAt)} ms, " +
                "added ${result.added} and removed ${result.removed} in ${elapsedMs(start)} ms"
        }
        onApplied(result)

        for (manga in added) {
            val restored = manga.url in missing
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

    private fun elapsedMs(start: Long, end: Long = System.nanoTime()) = (end - start) / 1_000_000

    /** Whether [url] is a manga below the current manga folder. Legacy folder names are relative to it. */
    private fun isInFolder(url: String, baseUrl: String): Boolean {
        if (!url.startsWith("content://") && !url.startsWith("file://")) return true
        return isFileUriInside(url, baseUrl)
    }

    /** The listed URL [url] refers to, also for document URIs spelled differently and legacy folder names. */
    private fun listedUrlOf(url: String, listed: Map<String, SManga>): String? {
        if (url in listed) return url
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return listed.keys.firstOrNull { areSameFileUris(url, it) }
        }
        return listed.values.firstOrNull { it.title == url && fileSystem.getMangaEntry(url) != null }?.url
    }

    private companion object {
        val mutex = Mutex()
    }
}
