package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.data.coil.ChapterCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

/**
 * A chapter shown by its cover, the way a volume sits on a shelf.
 *
 * A [wide] item has a row to itself: its cover stays book-sized in the middle and its title is set larger.
 */
@Composable
fun MangaChapterGridItem(
    cover: ChapterCover,
    title: String,
    read: Boolean,
    bookmark: Boolean,
    selected: Boolean,
    isOcrReady: Boolean,
    isOcrRunning: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val contentAlpha = if (read) DISABLED_ALPHA else 1f
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .selectedBackground(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(if (wide) 12.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = WideCoverMaxWidth / WideCoverFraction)
                .fillMaxWidth(if (wide) WideCoverFraction else 1f)
                .aspectRatio(MangaCover.Book.ratio)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val context = LocalContext.current
            AsyncImage(
                model = remember(cover) {
                    ImageRequest.Builder(context)
                        .data(cover)
                        .crossfade(true)
                        .build()
                },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha),
            )
            if (bookmark) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
            ChapterOcrIndicator(
                isOcrReady = isOcrReady,
                isOcrRunning = isOcrRunning,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp),
            )
        }
        Text(
            text = title,
            style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
            textAlign = if (wide) TextAlign.Center else TextAlign.Start,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (wide) 12.dp else 4.dp)
                .alpha(contentAlpha),
        )
    }
}

private const val WideCoverFraction = 0.93f
private val WideCoverMaxWidth = 360.dp
