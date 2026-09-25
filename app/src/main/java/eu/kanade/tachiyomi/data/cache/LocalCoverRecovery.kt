package eu.kanade.tachiyomi.data.cache

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.util.updateLocalCoverFromSourceFetch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Repairs local cover files and saved URIs without refreshing the library's chapter or metadata records. */
class LocalCoverRecovery(
    private val coverManager: LocalCoverManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun recover(manga: Manga, source: LocalSource): Boolean = withContext(dispatcher) {
        if (manga.source != source.id) return@withContext false
        // Shared across Library and Browse. Striped locks bound memory and serialize the same book;
        // the semaphore limits archive readers across both screens, including queued duplicate requests.
        locks[(manga.url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            permits.withPermit {
                if (coverCache.getCustomCoverFile(manga.id).isFile) return@withPermit true
                val existing = coverManager.find(manga.url)
                if (existing != null && existing.uri.toString() == manga.thumbnailUrl) return@withPermit true

                val sourceManga = manga.toSManga()
                if (existing == null) {
                    // Do not mistake the old, now-missing thumbnail URI for successful extraction.
                    sourceManga.thumbnail_url = null
                    source.getMangaUpdate(
                        manga = sourceManga,
                        chapters = emptyList(),
                        fetchDetails = false,
                        fetchChapters = true,
                    )
                }
                // Recheck after extraction: this also preserves an existing/manual cover discovered
                // while waiting for another screen's recovery work.
                val available = coverManager.find(manga.url) ?: return@withPermit false
                if (coverCache.getCustomCoverFile(manga.id).isFile) return@withPermit true
                sourceManga.thumbnail_url = available.uri.toString()
                manga.updateLocalCoverFromSourceFetch(source, sourceManga, updateManga, coverCache)
                true
            }
        }
    }

    private companion object {
        val permits = Semaphore(2)
        val locks = Array(64) { Mutex() }
    }
}
