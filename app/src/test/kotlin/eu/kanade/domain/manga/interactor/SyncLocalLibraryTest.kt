package eu.kanade.domain.manga.interactor

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.storage.isFileUriInside
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory

class SyncLocalLibraryTest {
    private val mangas = mockk<MangaRepository>()
    private val chapters = mockk<ChapterRepository>()
    private val sourceManager = mockk<SourceManager>()
    private val source = mockk<LocalSource>()
    private val fileSystem = mockk<LocalSourceFileSystem>()
    private val baseDirectory = mockk<UniFile>()
    private val storagePreferences = mockk<StoragePreferences>()
    private val storageManager = mockk<StorageManager>()
    private val networkToLocalManga = mockk<NetworkToLocalManga>()
    private val updateMangaFromRemote = mockk<UpdateMangaFromRemote>()
    private val known = InMemoryPreference<Set<String>>("known", null, emptySet())
    private val missing = InMemoryPreference<Set<String>>("missing", null, emptySet())

    /** Database rows by URL; a sync changes them in place. */
    private val rows = LinkedHashMap<String, Manga>()
    private var listed = listOf<String>()
    private var nextId = 100L

    private val sync = SyncLocalLibrary(
        mangas = mangas,
        chapters = chapters,
        sourceManager = sourceManager,
        fileSystem = fileSystem,
        storagePreferences = storagePreferences,
        storageManager = storageManager,
        networkToLocalManga = networkToLocalManga,
        setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>(relaxed = true),
        updateMangaFromRemote = updateMangaFromRemote,
    )

    @BeforeEach
    fun setUp() {
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        every { sourceManager.get(LocalSource.ID) } returns source
        every { fileSystem.getBaseDirectory() } returns baseDirectory
        every { baseDirectory.canRead() } returns true
        val baseUri = mockk<Uri>()
        every { baseUri.toString() } returns "$TREE/tree/primary%3ANew"
        every { baseDirectory.uri } returns baseUri
        every { fileSystem.getMangaEntry(any()) } returns null
        every { storagePreferences.localLibraryKnownFolders() } returns known
        every { storagePreferences.localLibraryMissingFolders() } returns missing
        coEvery { source.getPopularManga(1) } answers {
            MangasPage(listed.map { url -> SManga.create().also { it.url = url; it.title = url } }, false)
        }
        coEvery { mangas.getMangaBySourceId(LocalSource.ID) } answers { rows.values.toList() }
        coEvery { chapters.getChapterByMangaId(any(), any()) } returns emptyList()
        val inserted = slot<List<Manga>>()
        coEvery { networkToLocalManga(capture(inserted), any()) } answers {
            inserted.captured.map { row(it.url) }
        }
        val updates = slot<List<MangaUpdate>>()
        coEvery { mangas.updateAll(capture(updates)) } answers {
            updates.captured.forEach { update ->
                val (url, manga) = rows.entries.single { it.value.id == update.id }
                rows[url] = manga.copy(
                    favorite = update.favorite ?: manga.favorite,
                    dateAdded = update.dateAdded ?: manga.dateAdded,
                )
            }
            true
        }
        coEvery { updateMangaFromRemote(any<Manga>(), any(), any(), any(), any(), any()) } returns
            Result.failure(Exception("not needed"))
    }

    @Test
    fun `the first sync adds new folders but not rows that were in the library before`() = runTest {
        row("browsed")
        row("removed").also { coEvery { chapters.getChapterByMangaId(it.id, any()) } returns listOf(Chapter.create()) }
        row("favorite", favorite = true)
        row("gone", favorite = true)
        listed = listOf("new", "browsed", "removed", "favorite")

        assertEquals(SyncLocalLibrary.Result(added = 2, removed = 1), sync.await())

        assertEquals(setOf("new", "browsed", "favorite"), favorites())
        assertEquals(setOf("new", "browsed", "removed", "favorite"), known.get())
        assertEquals(setOf("gone"), missing.get())
    }

    @Test
    fun `manga the user removed stay removed and missing folders come back`() = runTest {
        row("kept", favorite = true)
        row("gone", favorite = true, dateAdded = 5L)
        listed = listOf("kept", "gone")
        sync.await()

        rows["kept"] = rows.getValue("kept").copy(favorite = false)
        listed = listOf("kept")
        assertEquals(SyncLocalLibrary.Result(added = 0, removed = 1), sync.await())
        assertEquals(emptySet<String>(), favorites())

        listed = listOf("kept", "gone", "fresh")
        assertEquals(SyncLocalLibrary.Result(added = 2, removed = 0), sync.await())
        assertEquals(setOf("gone", "fresh"), favorites())
        assertEquals(5L, rows.getValue("gone").dateAdded)
        assertEquals(emptySet<String>(), missing.get())
    }

    @Test
    fun `a folder opened from Browse before a sync listed it is added`() = runTest {
        listed = listOf("old")
        sync.await()

        row("opened").also { coEvery { chapters.getChapterByMangaId(it.id, any()) } returns listOf(Chapter.create()) }
        listed = listOf("old", "opened")
        sync.await()

        assertEquals(setOf("old", "opened"), favorites())
    }

    @Test
    fun `an unreadable folder leaves the library unchanged`() = runTest {
        row("favorite", favorite = true)
        every { baseDirectory.canRead() } returns false

        assertNull(sync.await())
        assertEquals(setOf("favorite"), favorites())
        coVerify(exactly = 0) { mangas.updateAll(any()) }
    }

    @Test
    fun `document URIs spelled differently still match their listed folder`() = runTest {
        val stored = "content://com.android.externalstorage.documents/tree/primary%3AManga/document/primary%3AManga%2FA"
        val listedUrl = "content://com.android.externalstorage.documents/document/primary%3AManga%2FA"
        row(stored, favorite = true)
        listed = listOf(listedUrl)

        assertEquals(SyncLocalLibrary.Result(added = 0, removed = 0), sync.await())
        assertEquals(setOf(stored), favorites())
    }

    @Test
    fun `choosing another manga folder keeps the manga from the earlier one`() = runTest {
        val kept = row("$TREE/tree/primary%3AOld/document/primary%3AOld%2FA", favorite = true)
        val back = row("$TREE/tree/primary%3AOld/document/primary%3AOld%2FB")
        val deleted = row("$TREE/tree/primary%3AOld/document/primary%3AOld%2FC")
        val inNew = row("$TREE/tree/primary%3ANew/document/primary%3ANew%2FD", favorite = true)
        known.set(setOf(kept.url, back.url, deleted.url, inNew.url))
        missing.set(setOf(back.url, deleted.url))
        every { storageManager.getFileFromUri(back.url) } returns mockk { every { exists() } returns true }
        every { storageManager.getFileFromUri(deleted.url) } returns mockk { every { exists() } returns false }
        listed = emptyList()

        assertEquals(SyncLocalLibrary.Result(added = 1, removed = 1), sync.await())

        assertEquals(setOf(kept.url, back.url), favorites())
        assertEquals(setOf(deleted.url, inNew.url), missing.get())
    }

    @Test
    fun `folder containment follows document ids and file paths`() {
        assertTrue(isFileUriInside("$TREE/tree/primary%3AM/document/primary%3AM%2FP%2FS", "$TREE/tree/primary%3AM"))
        assertTrue(isFileUriInside("$TREE/tree/primary%3A/document/primary%3AM", "$TREE/tree/primary%3A"))
        assertTrue(isFileUriInside("file:///sdcard/M/S", "file:///sdcard/M/"))
        assertFalse(isFileUriInside("$TREE/tree/primary%3AM2/document/primary%3AM2%2FS", "$TREE/tree/primary%3AM"))
        assertFalse(isFileUriInside("$TREE/tree/primary%3AM", "$TREE/tree/primary%3AM"))
        assertFalse(isFileUriInside("file:///sdcard/M2/S", "file:///sdcard/M"))
    }

    companion object {
        private const val TREE = "content://com.android.externalstorage.documents"

        @JvmStatic
        @BeforeAll
        fun registerCustomInfo() {
            Injekt.addSingletonFactory {
                GetCustomMangaInfo(
                    object : CustomMangaRepository {
                        override fun get(mangaId: Long) = null
                        override fun set(mangaInfo: CustomMangaInfo) = Unit
                    },
                )
            }
        }
    }

    private fun favorites() = rows.values.filter { it.favorite }.map { it.url }.toSet()

    private fun row(url: String, favorite: Boolean = false, dateAdded: Long = 0L): Manga {
        return Manga.create()
            .copy(id = nextId++, url = url, ogTitle = url, source = LocalSource.ID, favorite = favorite, dateAdded = dateAdded)
            .also { rows[url] = it }
    }
}
