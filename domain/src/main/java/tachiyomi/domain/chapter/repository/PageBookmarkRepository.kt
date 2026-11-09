package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.PageBookmark

interface PageBookmarkRepository {

    /** Bookmarked pages of every chapter of the manga, ordered by chapter and page. */
    fun subscribeByMangaId(mangaId: Long): Flow<List<PageBookmark>>

    suspend fun insert(chapterId: Long, pageIndex: Int)

    suspend fun delete(chapterId: Long, pageIndex: Int)
}
