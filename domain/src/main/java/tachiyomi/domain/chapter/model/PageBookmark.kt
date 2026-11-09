package tachiyomi.domain.chapter.model

data class PageBookmark(
    val chapterId: Long,
    val pageIndex: Int,
    val dateAdded: Long,
)
