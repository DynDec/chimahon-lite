package tachiyomi.data.chapter

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.PageBookmark
import tachiyomi.domain.chapter.repository.PageBookmarkRepository

class PageBookmarkRepositoryImpl(
    private val handler: DatabaseHandler,
) : PageBookmarkRepository {

    override fun subscribeByMangaId(mangaId: Long): Flow<List<PageBookmark>> {
        return handler.subscribeToList {
            page_bookmarksQueries.getByMangaId(mangaId) { chapterId, pageIndex, dateAdded ->
                PageBookmark(chapterId, pageIndex.toInt(), dateAdded)
            }
        }
    }

    override suspend fun insert(chapterId: Long, pageIndex: Int) {
        handler.await {
            page_bookmarksQueries.insert(chapterId, pageIndex.toLong(), System.currentTimeMillis())
        }
    }

    override suspend fun delete(chapterId: Long, pageIndex: Int) {
        handler.await {
            page_bookmarksQueries.delete(chapterId, pageIndex.toLong())
        }
    }
}
