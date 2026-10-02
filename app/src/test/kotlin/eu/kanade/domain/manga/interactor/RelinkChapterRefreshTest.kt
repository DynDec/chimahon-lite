package eu.kanade.domain.manga.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.retainedByRelink
import tachiyomi.domain.chapter.model.withRelinkRetention
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem

class RelinkChapterRefreshTest {
    private val manga = Manga.create().copy(id = 5, url = "file:///new", source = LocalSource.ID, ogTitle = "Book")
    private val original = Chapter.create().copy(
        id = 17,
        mangaId = 5,
        url = "file:///old/01.cbz",
        name = "01",
        read = true,
        lastPageRead = 12,
        memo = JsonObject(mapOf("annotation" to JsonPrimitive("kept"))).withRelinkRetention(true),
    )
    private val repository = mockk<ChapterRepository>(relaxed = true)
    private val update = mockk<UpdateChapter>(relaxed = true)
    private val get = mockk<GetChaptersByMangaId>()
    private val fs = mockk<LocalSourceFileSystem>(relaxed = true)
    private val preferences = mockk<LibraryPreferences>(relaxed = true)
    private val source = mockk<LocalSource> { every { id } returns LocalSource.ID }
    private val excluded = mockk<GetExcludedScanlators>(relaxed = true)

    private fun sync() = SyncChaptersWithSource(
        mockk<DownloadManager>(relaxed = true), mockk<DownloadProvider>(relaxed = true), repository,
        ShouldUpdateDbChapter(), mockk<UpdateManga>(relaxed = true), update, get, excluded, preferences, fs,
    )

    @Test
    fun `refresh cannot delete retained records and their cascading history`() = runTest {
        coEvery { get.await(5, false) } returns listOf(original)
        sync().await(emptyList(), manga, source)
        coVerify(exactly = 0) { repository.removeChaptersWithIds(any()) }
    }

    @Test
    fun `restored chapter keeps annotations and protection during metadata refresh`() = runTest {
        coEvery { get.await(5, false) } returns listOf(original)
        every { preferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        val sourceChapter = original.toSChapter().apply {
            name = "New title"
            memo = JsonObject(emptyMap())
        }
        sync().await(listOf(sourceChapter), manga, source)
        val updates = slot<List<ChapterUpdate>>()
        coVerify { update.awaitAll(capture(updates)) }
        assertEquals(17L, updates.captured.single().id)
        assertTrue(original.copy(memo = updates.captured.single().memo!!).retainedByRelink)
        assertEquals(JsonPrimitive("kept"), updates.captured.single().memo!!["annotation"])
        coVerify(exactly = 0) { repository.removeChaptersWithIds(any()) }
    }

    @Test
    fun `ordinary local chapters still follow the existing removal policy`() = runTest {
        coEvery { get.await(5, false) } returns listOf(original.copy(memo = JsonObject(emptyMap())))
        every { preferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        sync().await(emptyList(), manga, source)
        coVerify { repository.removeChaptersWithIds(listOf(17)) }
    }
}
