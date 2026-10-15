package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.collections.immutable.ImmutableList
import mihon.core.archive.TocEntry

/**
 * Lists the current volume's own table of contents. The entry containing [currentPageIndex] is
 * highlighted and scrolled into view.
 */
@Composable
fun ContentsDialog(
    onDismissRequest: () -> Unit,
    entries: ImmutableList<TocEntry>,
    currentPageIndex: Int,
    onClickEntry: (TocEntry) -> Unit,
) {
    val currentEntryIndex = entries.indexOfLast { it.pageIndex <= currentPageIndex }
    val state = rememberLazyListState(currentEntryIndex.coerceAtLeast(0))

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            itemsIndexed(
                items = entries,
                key = { index, _ -> "contents-$index" },
            ) { index, entry ->
                ContentsItem(
                    entry = entry,
                    isCurrent = index == currentEntryIndex,
                    onClick = { onClickEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun ContentsItem(
    entry: TocEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = 16.dp + 16.dp * entry.level,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.title,
            modifier = Modifier.weight(1f),
            color = color,
            fontWeight = if (isCurrent) FontWeight.Bold else null,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = (entry.pageIndex + 1).toString(),
            modifier = Modifier.padding(start = 16.dp),
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
