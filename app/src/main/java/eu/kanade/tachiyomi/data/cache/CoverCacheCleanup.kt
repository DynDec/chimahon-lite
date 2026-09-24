package eu.kanade.tachiyomi.data.cache

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import androidx.core.net.toUri
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.storage.CoverFileCleanup
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Invoked only by the Storage Usage action; there is no startup or periodic scan. */
class CoverCacheCleanup(private val context: Context) {
    suspend fun clean(): CoverFileCleanup.Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val coroutineContext = currentCoroutineContext()
            val coverCache = Injekt.get<CoverCache>()
            val localDirectory = File(context.cacheDir, "local_covers")
            val directories = listOf(
                localDirectory,
                coverCache.cacheDir,
                coverCache.customCoverCacheDir,
            )

            // Snapshot before querying owners. Files created or replaced during the scan are skipped.
            val candidates = buildMap {
                for (directory in directories) {
                    coroutineContext.ensureActive()
                    for (file in directory.listFiles().orEmpty()) {
                        val expectedName = if (directory == localDirectory) LOCAL_COVER_NAME else COVER_NAME
                        if (!expectedName.matches(file.name)) continue
                        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                        try {
                            put(file, stat(file))
                        } catch (_: IOException) {
                            // A cover may have been evicted while listing the cache.
                        }
                    }
                }
            }

            // Keep all database owners, including browsed items outside the library and custom covers.
            // Any database failure aborts the operation before deleting anything.
            val manga = Injekt.get<MangaRepository>().getAll()
            val referenced = buildSet {
                fun keepLocal(url: String) {
                    val hash = Hash.sha256(url)
                    add(File(localDirectory, "cover-$hash.jpg"))
                    add(File(localDirectory, "cover-$hash.cbi"))
                }
                fun keepThumbnail(url: String?) {
                    if (url == null) return
                    val path = when {
                        url.startsWith("file:") -> url.toUri().path
                        url.startsWith("/") -> url
                        else -> null
                    }
                    if (path != null) add(File(path))
                }
                for (item in manga) {
                    coverCache.getCoverFile(item.thumbnailUrl)?.let(::add)
                    add(coverCache.getCustomCoverFile(item.id))
                    keepThumbnail(item.thumbnailUrl)
                    if (item.source == LocalSource.ID) keepLocal(item.url)
                }
                // A custom cover can be the only copy of a user-supplied image, including legacy
                // entries. It may share bytes, but is never removed as an orphan by this action.
                addAll(candidates.keys.filter { it.parentFile == coverCache.customCoverCacheDir })

                val entries = Injekt.get<LocalSourceFileSystem>().getBaseDirectory()?.listFiles()
                if (entries == null) {
                    // Lost storage permission or an unavailable provider is not evidence of orphaning.
                    addAll(candidates.keys.filter { it.parentFile == localDirectory })
                } else {
                    for (entry in entries) {
                        keepLocal(entry.uri.toString())
                        entry.name?.let(::keepLocal) // Legacy relative identities.
                    }
                }
            }
            CoverFileCleanup(::stat).clean(candidates, referenced) { coroutineContext.ensureActive() }
        }
    }

    private fun stat(file: File): CoverFileCleanup.FileState {
        val state = try {
            Os.lstat(file.absolutePath)
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
        return CoverFileCleanup.FileState(
            identity = "${state.st_dev}:${state.st_ino}",
            links = state.st_nlink,
            size = state.st_size,
            allocatedBytes = state.st_blocks * 512L,
            modified = state.st_mtime,
            device = state.st_dev,
        )
    }

    private companion object {
        val mutex = Mutex()
        val LOCAL_COVER_NAME = Regex("cover-[a-fA-F0-9]{64}\\.(jpg|cbi)")
        val COVER_NAME = Regex("[a-fA-F0-9]{32}")
    }
}
