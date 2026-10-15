package eu.kanade.tachiyomi.data.cache

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.storage.CoverFileStorage
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.LocalCoverReference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Points local manga at their folder or first-chapter cover, which is read on demand, and retires covers
 * that older versions copied into the app cache. Only touches entries without a usable cover reference.
 */
class LocalCoverResolver(
    private val coverManager: LocalCoverManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val context: Context = Injekt.get<Application>(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun needsResolution(manga: Manga): Boolean =
        manga.source == LocalSource.ID &&
            (
                manga.ogThumbnailUrl.isNullOrEmpty() ||
                    LocalCoverReference.isLegacyGeneratedCover(manga.ogThumbnailUrl) ||
                    LocalCoverReference.isOutdated(manga.ogThumbnailUrl)
                )

    suspend fun resolve(manga: Manga, source: LocalSource): Boolean = withContext(dispatcher) {
        if (!needsResolution(manga)) return@withContext true
        // Shared across Library and Browse: one resolution per book, and a bounded number of archive reads.
        locks[(manga.url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            permits.withPermit {
                val cover = source.resolveCover(manga.url) ?: return@withPermit false
                retireLegacyCover(manga, cover)
                updateManga.await(
                    MangaUpdate(id = manga.id, thumbnailUrl = cover, coverLastModified = Instant.now().toEpochMilli()),
                )
            }
        }
    }

    /**
     * Resolves a local cover again after its file failed to load, e.g. because the first chapter was renamed
     * or a backup was restored on another device. Only updates the entry when the cover actually changed.
     */
    suspend fun refreshStale(manga: Manga, source: LocalSource): Boolean = withContext(dispatcher) {
        locks[(manga.url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            permits.withPermit {
                val cover = source.resolveCover(manga.url)
                if (cover == null || cover == manga.ogThumbnailUrl) return@withPermit false
                retireLegacyCover(manga, cover)
                updateManga.await(
                    MangaUpdate(id = manga.id, thumbnailUrl = cover, coverLastModified = Instant.now().toEpochMilli()),
                )
            }
        }
    }

    /**
     * Older versions stored generated covers and covers the user picked in the same cache files. Before a
     * manga stops referencing one, keep it as the custom cover unless it is the same image as [newCover].
     */
    fun retireLegacyCover(manga: Manga, newCover: String?) {
        val url = manga.ogThumbnailUrl?.takeIf { LocalCoverReference.isLegacyGeneratedCover(it) } ?: return
        val legacy = runCatching { File(URI(url)) }.getOrNull()?.takeIf { it.isFile } ?: return
        val custom = coverCache.getCustomCoverFile(manga.id)
        // Encrypted .cbi covers were only ever generated from encrypted archives.
        val userChosen = legacy.extension == "jpg" && !custom.exists() && !sameImage(legacy, newCover)
        if (userChosen) {
            custom.parentFile?.mkdirs()
            CoverFileStorage.replace(legacy, custom)
        } else {
            CoverFileStorage.delete(legacy)
        }
    }

    private fun sameImage(file: File, cover: String?): Boolean {
        val bytes = try {
            when {
                cover == null -> null
                LocalCoverReference.isReference(cover) -> coverManager.read(cover)
                else -> context.contentResolver.openInputStream(cover.toUri())?.use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        } ?: return false
        return file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)
    }

    companion object {
        private val permits = Semaphore(4)
        private val locks = Array(64) { Mutex() }
        private val refreshedAfterFailure = ConcurrentHashMap.newKeySet<Long>()
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Called when a local cover fails to load; retries resolution once per manga per process. */
        fun onLoadFailed(mangaId: Long) {
            if (!refreshedAfterFailure.add(mangaId)) return
            refreshScope.launch {
                try {
                    val sourceManager = Injekt.get<SourceManager>()
                    sourceManager.isInitialized.first { it }
                    val source = sourceManager.get(LocalSource.ID) as? LocalSource ?: return@launch
                    val manga = Injekt.get<MangaRepository>().getMangaById(mangaId)
                    LocalCoverResolver().refreshStale(manga, source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Error refreshing local cover for $mangaId" }
                }
            }
        }
    }
}
