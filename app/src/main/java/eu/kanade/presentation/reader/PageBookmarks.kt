package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.bookmark.PageBookmarkGroup
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.chapter.model.PageBookmark
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

/**
 * Sheet showing [content], with the page bookmarks in a second tab when [bookmarksTab] is given.
 */
@Composable
fun ReaderListSheet(
    onDismissRequest: () -> Unit,
    title: String,
    bookmarksTab: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        if (bookmarksTab == null) {
            content()
            return@AdaptiveSheet
        }

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        Column {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                divider = {},
            ) {
                listOf(title, stringResource(MR.strings.bookmarks)).forEachIndexed { index, tabTitle ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { TabText(text = tabTitle) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider()

            if (selectedTab == 0) content() else bookmarksTab()
        }
    }
}

@Composable
fun PageBookmarkList(
    groups: ImmutableList<PageBookmarkGroup>,
    currentChapterId: Long?,
    currentPageIndex: Int,
    thumbnail: (PageBookmark) -> File,
    onClickBookmark: (PageBookmark) -> Unit,
    onRemoveBookmark: (PageBookmark) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        groups.forEach { group ->
            item(key = "bookmark-chapter-${group.chapterId}") {
                Text(
                    text = group.chapterName,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            items(
                items = group.bookmarks,
                key = { "bookmark-${it.chapterId}-${it.pageIndex}" },
            ) { bookmark ->
                PageBookmarkItem(
                    bookmark = bookmark,
                    isCurrent = bookmark.chapterId == currentChapterId && bookmark.pageIndex == currentPageIndex,
                    thumbnail = thumbnail(bookmark),
                    onClick = { onClickBookmark(bookmark) },
                    onRemove = { onRemoveBookmark(bookmark) },
                )
            }
        }
    }
}

@Composable
private fun PageBookmarkItem(
    bookmark: PageBookmark,
    isCurrent: Boolean,
    thumbnail: File,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbnail,
            contentDescription = null,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = stringResource(MR.strings.player_sheets_stats_page_chip, bookmark.pageIndex + 1),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            color = color,
            fontWeight = if (isCurrent) FontWeight.Bold else null,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.BookmarkRemove,
                contentDescription = stringResource(MR.strings.action_remove_bookmark),
            )
        }
    }
}
