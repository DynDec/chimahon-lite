package eu.kanade.domain.manga.interactor

import android.net.Uri
import chimahon.ocr.OcrCacheManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.OcrQueueItem
import eu.kanade.tachiyomi.data.ocr.OcrQueueStatus
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.retainedByRelink
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

class RelinkLocalMangaTest {
    private val manga = Manga.create().copy(id = 5, source = LocalSource.ID, url = "file:///old", ogTitle = "Book", notes = "notes", viewerFlags = 42)
    private val chapter = Chapter.create().copy(id = 17, mangaId = 5, url = "file:///old/01.cbz", name = "01", read = true, bookmark = true, lastPageRead = 12, isOcrReady = true)
    private val mangas = mockk<MangaRepository>()
    private val chapters = mockk<ChapterRepository>()
    private val handler = mockk<DatabaseHandler>()
    private val sources = mockk<SourceManager>()
    private val fs = mockk<LocalSourceFileSystem>()
    private val covers = mockk<LocalCoverManager>()
    private val cache = mockk<OcrCacheManager>(relaxed = true)
    private val ocr = mockk<OcrManager>()
    private val source = mockk<LocalSource>()
    private val destination = directory("file:///new")
    private var newBytes = "same"
    private lateinit var relink: RelinkLocalManga

    @BeforeEach
    fun setup() {
        coEvery { mangas.getMangaById(5) } returns manga
        coEvery { mangas.getMangaBySourceId(LocalSource.ID) } returns listOf(manga)
        coEvery { mangas.update(any()) } returns true
        coEvery { chapters.getChapterByMangaId(5, false) } returns listOf(chapter)
        coEvery { chapters.updateAll(any()) } just Runs
        coEvery { chapters.addAll(any()) } answers { firstArg<List<Chapter>>().mapIndexed { index, ch -> ch.copy(id = 100 + index.toLong()) } }
        every { sources.get(LocalSource.ID) } returns source
        every { fs.getMangaDirectory(manga.url) } returns directory(manga.url)
        every { fs.getMangaDirectory("file:///new") } returns destination
        every { fs.getChapterFile(manga.url, chapter.url) } answers { file(chapter.url, "same") }
        every { fs.getChapterFile("file:///new", "file:///new/01.cbz") } answers { file("file:///new/01.cbz", newBytes) }
        every { covers.find(any()) } returns null
        every { covers.copyForRelink(any(), any()) } returns null
        every { ocr.queueState } returns MutableStateFlow(emptyList())
        coEvery { source.getMangaUpdate(any(), any(), false, true) } returns SMangaUpdate(SManga.create(), listOf(sChapter("file:///new/01.cbz")))
        coEvery { handler.await<Unit>(true, any()) } coAnswers {
            secondArg<suspend Database.() -> Unit>().invoke(mockk())
        }
        relink = RelinkLocalManga(mangas, chapters, handler, sources, fs, covers, cache, ocr)
    }

    @Test
    fun `relink preserves manga and chapter IDs without overwriting progress or metadata`() = runTest {
        val preview = relink.preview(5, "file:///new")
        relink.apply(preview, false)
        val update = slot<MangaUpdate>()
        val chapterUpdates = slot<List<ChapterUpdate>>()
        coVerify { mangas.update(capture(update)) }
        coVerify { chapters.updateAll(capture(chapterUpdates)) }
        assertEquals(MangaUpdate(id = 5, url = "file:///new"), update.captured)
        assertEquals(17L, chapterUpdates.captured.single().id)
        assertEquals("file:///new/01.cbz", chapterUpdates.captured.single().url)
        assertNull(chapterUpdates.captured.single().read)
        assertNull(chapterUpdates.captured.single().bookmark)
        assertNull(chapterUpdates.captured.single().lastPageRead)
        assertNull(chapterUpdates.captured.single().isOcrReady)
        coVerify { cache.preserveForRelink(manga, chapter, source) }
        coVerify { handler.await<Unit>(true, any()) }
        coVerify(exactly = 0) {
            mangas.deleteManga(any())
            chapters.removeChaptersWithIds(any())
        }
    }

    @Test
    fun `changed content keeps old chapter and OCR separate from new file`() = runTest {
        newBytes = "changed"
        val preview = relink.preview(5, "file:///new")
        assertEquals(0, preview.matchedCount)
        assertEquals(1, preview.retainedCount)
        assertEquals(1, preview.addedCount)
        relink.apply(preview, false)
        val updates = slot<List<ChapterUpdate>>()
        coVerify { chapters.updateAll(capture(updates)) }
        assertNull(updates.captured.single().url)
        assertTrue(chapter.copy(memo = updates.captured.single().memo!!).retainedByRelink)
        coVerify { chapters.addAll(match { it.size == 1 && !it.single().read && !it.single().isOcrReady && it.single().lastPageRead == 0L }) }
        coVerify(exactly = 0) { cache.preserveForRelink(any(), any(), any()) }
    }

    @Test
    fun `retained legacy chapter stays anchored to its original file`() = runTest {
        val legacy = chapter.copy(url = "old/01.cbz")
        coEvery { chapters.getChapterByMangaId(5, false) } returns listOf(legacy)
        every { fs.getChapterFile(manga.url, legacy.url) } answers { file(chapter.url, "same") }
        newBytes = "changed"
        relink.apply(relink.preview(5, "file:///new"), false)
        val updates = slot<List<ChapterUpdate>>()
        coVerify { chapters.updateAll(capture(updates)) }
        assertEquals(chapter.url, updates.captured.single().url)
        assertTrue(legacy.copy(memo = updates.captured.single().memo!!).retainedByRelink)
    }

    @Test
    fun `unavailable ambiguous legacy paths cannot be redirected to replacement content`() = runTest {
        val legacy = chapter.copy(url = "old/01.cbz")
        coEvery { chapters.getChapterByMangaId(5, false) } returns listOf(legacy, legacy.copy(id = 18, url = "other/01.cbz"))
        every { fs.getChapterFile(manga.url, "old/01.cbz") } returns null
        every { fs.getChapterFile(manga.url, "other/01.cbz") } returns null
        val error = assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.preview(5, "file:///new") } }
        assertEquals(RelinkLocalManga.Error.Unavailable, error.reason)
        coVerify(exactly = 0) { chapters.updateAll(any()) }
    }

    @Test
    fun `unavailable originals cannot silently reuse page positions and OCR`() = runTest {
        every { fs.getChapterFile(manga.url, chapter.url) } returns null
        val preview = relink.preview(5, "file:///new")
        assertEquals(1, preview.unverifiedCount)
        val error = assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.apply(preview, false) } }
        assertEquals(RelinkLocalManga.Error.ConfirmationRequired, error.reason)
        coVerify(exactly = 0) {
            mangas.update(any())
            chapters.updateAll(any())
        }
        relink.apply(preview, true)
        coVerify(exactly = 1) { mangas.update(any()) }
    }

    @Test
    fun `files changing after preview leave the link and records unchanged`() = runTest {
        val preview = relink.preview(5, "file:///new")
        newBytes = "changed after preview"
        val error = assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.apply(preview, true) } }
        assertEquals(RelinkLocalManga.Error.Changed, error.reason)
        coVerify(exactly = 0) {
            mangas.update(any())
            chapters.updateAll(any())
        }
    }

    @Test
    fun `another entry owning the destination is never deleted`() = runTest {
        coEvery { mangas.getMangaBySourceId(LocalSource.ID) } returns listOf(manga, manga.copy(id = 6, url = "file:///new"))
        val error = assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.preview(5, "file:///new") } }
        assertEquals(RelinkLocalManga.Error.AlreadyLinked, error.reason)
        coVerify(exactly = 0) {
            mangas.deleteManga(any())
            mangas.update(any())
        }
    }

    @Test
    fun `inaccessible or empty destination cannot change the link`() = runTest {
        every { fs.getMangaDirectory("file:///new") } returns null
        assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.preview(5, "file:///new") } }
        every { fs.getMangaDirectory("file:///new") } returns destination
        coEvery { source.getMangaUpdate(any(), any(), false, true) } returns SMangaUpdate(SManga.create(), emptyList())
        val error = assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.preview(5, "file:///new") } }
        assertEquals(RelinkLocalManga.Error.EmptyFolder, error.reason)
        coVerify(exactly = 0) { mangas.update(any()) }
    }

    @Test
    fun `queued OCR blocks relinking before any writes`() = runTest {
        every { ocr.queueState } returns MutableStateFlow(listOf(OcrQueueItem(manga, chapter, 0f, 0, 1, OcrQueueStatus.PENDING)))
        val error = assertThrows(RelinkLocalManga.RelinkException::class.java) { kotlinx.coroutines.runBlocking { relink.preview(5, "file:///new") } }
        assertEquals(RelinkLocalManga.Error.OcrBusy, error.reason)
        coVerify(exactly = 0) {
            mangas.update(any())
            chapters.updateAll(any())
        }
    }

    @Test
    fun `directory chapters compare page content without treating OCR sidecars as pages`() = runTest {
        val oldDirectory = directory(chapter.url)
        val newDirectory = directory("file:///new/01.cbz")
        every { oldDirectory.listFiles() } returns arrayOf(file("file:///old/01.cbz/01.jpg", "page"), file("file:///old/01.cbz/.ocr_cache.json", "old cache"))
        every { newDirectory.listFiles() } returns arrayOf(file("file:///new/01.cbz/01.jpg", "page"), file("file:///new/01.cbz/.ocr_cache.json", "new cache"))
        every { fs.getChapterFile(manga.url, chapter.url) } returns oldDirectory
        every { fs.getChapterFile("file:///new", "file:///new/01.cbz") } returns newDirectory
        val preview = relink.preview(5, "file:///new")
        assertEquals(1, preview.matchedCount)
        assertEquals(0, preview.unverifiedCount)
    }

    private fun uri(url: String): Uri {
        val result = mockk<Uri>()
        every { result.toString() } returns url
        return result
    }
    private fun directory(url: String): UniFile {
        val location = uri(url)
        val result = mockk<UniFile>()
        every { result.uri } returns location
        every { result.name } returns url.substringAfterLast('/')
        every { result.canRead() } returns true
        every { result.isDirectory } returns true
        every { result.filePath } returns null
        return result
    }
    private fun file(url: String, content: String): UniFile {
        val location = uri(url)
        val result = mockk<UniFile>()
        every { result.uri } returns location
        every { result.name } returns url.substringAfterLast('/')
        every { result.isDirectory } returns false
        every { result.openInputStream() } answers { content.byteInputStream() }
        return result
    }
    private fun sChapter(url: String) = SChapter.create().apply {
        this.url = url
        name = "01"
    }
}
