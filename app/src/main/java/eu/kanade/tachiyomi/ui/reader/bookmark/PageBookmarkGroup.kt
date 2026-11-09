package eu.kanade.tachiyomi.ui.reader.bookmark

import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.chapter.model.PageBookmark

/** The bookmarked pages of one chapter. */
data class PageBookmarkGroup(
    val chapterId: Long,
    val chapterName: String,
    val bookmarks: ImmutableList<PageBookmark>,
)
