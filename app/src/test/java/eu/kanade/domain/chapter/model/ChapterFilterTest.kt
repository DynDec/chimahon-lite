package eu.kanade.domain.chapter.model

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterFilterTest {

    private val chapters = listOf(
        Chapter.create().copy(id = 3, chapterNumber = 3.0, read = false, bookmark = true),
        Chapter.create().copy(id = 1, chapterNumber = 1.0, read = true, bookmark = true),
        Chapter.create().copy(id = 2, chapterNumber = 2.0, read = false, bookmark = false),
    )

    @Test
    fun legacyDownloadedFilterCannotHideChaptersOrQueryDownloads() {
        val downloads = mockk<DownloadManager>()
        for (flag in listOf(Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)) {
            val manga = Manga.create().copy(chapterFlags = Manga.create().chapterFlags or flag)
            assertEquals(listOf(1L, 2L, 3L), chapters.applyFilters(manga, downloads, emptyMap()).map { it.id })
            assertEquals(listOf(1L, 2L, 3L), items().applyFilters(manga).map { it.id }.toList())
        }
        verify { downloads wasNot Called }
    }

    @Test
    fun unreadBookmarkAndSortFiltersStillWorkWithRetiredBits() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC or
                Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SHOW_NOT_DOWNLOADED,
        )
        assertEquals(listOf(3L, 2L), chapters.applyFilters(manga, mockk(), emptyMap()).map { it.id })
        assertEquals(listOf(3L, 2L), items().applyFilters(manga).map { it.id }.toList())

        val bookmarked = manga.copy(chapterFlags = manga.chapterFlags or Manga.CHAPTER_SHOW_BOOKMARKED)
        assertEquals(listOf(3L), chapters.applyFilters(bookmarked, mockk(), emptyMap()).map { it.id })
        assertEquals(listOf(3L), items().applyFilters(bookmarked).map { it.id }.toList())
    }

    private fun items() = chapters.map {
        ChapterList.Item(
            chapter = it,
            downloadState = Download.State.NOT_DOWNLOADED,
            downloadProgress = 0,
            sourceName = null,
            showScanlator = false,
        )
    }
}
