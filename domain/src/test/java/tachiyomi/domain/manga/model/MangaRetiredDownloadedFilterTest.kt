package tachiyomi.domain.manga.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState

class MangaRetiredDownloadedFilterTest {

    @Test
    fun `restored downloaded filters are ignored while other chapter flags survive`() {
        for (retiredFilter in listOf(Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)) {
            val flags = retiredFilter or Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SHOW_BOOKMARKED or
                Manga.CHAPTER_SORTING_UPLOAD_DATE or Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_DISPLAY_NUMBER
            val manga = Manga.create().copy(chapterFlags = flags)

            assertEquals(Manga.SHOW_ALL, manga.downloadedFilterRaw)
            assertEquals(TriState.ENABLED_IS, manga.unreadFilter)
            assertEquals(TriState.ENABLED_IS, manga.bookmarkedFilter)
            assertEquals(Manga.CHAPTER_SORTING_UPLOAD_DATE, manga.sorting)
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, manga.displayMode)
            assertEquals(flags, manga.chapterFlags)
        }
    }
}
