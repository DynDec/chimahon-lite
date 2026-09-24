package tachiyomi.domain.manga.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaChapterSortingTest {

    @Test
    fun `legacy source sort resolves to chapter number ascending`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SHOW_UNREAD,
        )

        assertEquals(Manga.CHAPTER_SORTING_NUMBER, manga.sorting)
        assertFalse(manga.sortDescending())
        assertEquals(Manga.CHAPTER_SHOW_UNREAD, manga.unreadFilterRaw)
    }

    @Test
    fun `other sorting modes retain their direction`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_UPLOAD_DATE or Manga.CHAPTER_SORT_DESC,
        )

        assertEquals(Manga.CHAPTER_SORTING_UPLOAD_DATE, manga.sorting)
        assertTrue(manga.sortDescending())
    }

    @Test
    fun `new manga start with chapter number ascending`() {
        val manga = Manga.create()

        assertEquals(Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC, manga.chapterFlags)
        assertFalse(manga.sortDescending())
    }
}
