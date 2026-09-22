package eu.kanade.tachiyomi.ui.ocr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.OcrQueueItem
import eu.kanade.tachiyomi.data.ocr.OcrQueueStatus
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object OcrQueueScreen : Screen() {
    @Suppress("unused")
    private fun readResolve(): Any = OcrQueueScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { OcrQueueScreenModel() }
        val ocrQueue by screenModel.queueState.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.ocr_queue),
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            if (ocrQueue.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.ocr_queue_empty,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                OcrQueueSection(
                    ocrQueue = ocrQueue,
                    onCancelClick = screenModel::cancel,
                    onRetryClick = screenModel::retry,
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                )
            }
        }
    }
}

private class OcrQueueScreenModel(
    private val ocrManager: OcrManager = Injekt.get(),
) : ScreenModel {
    val queueState = ocrManager.queueState

    fun cancel(chapterId: Long) {
        screenModelScope.launch { ocrManager.cancelChapter(chapterId) }
    }

    fun retry(chapterId: Long) {
        screenModelScope.launch { ocrManager.retryChapter(chapterId) }
    }
}

@Composable
private fun OcrQueueSection(
    ocrQueue: List<OcrQueueItem>,
    onCancelClick: (Long) -> Unit,
    onRetryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.ocr_processing),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Pill(
                text = "${ocrQueue.size}",
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                fontSize = 12.sp,
            )
        }

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            ocrQueue.forEach { item ->
                OcrQueueItemRow(
                    item = item,
                    onCancelClick = { onCancelClick(item.chapter.id) },
                    onRetryClick = { onRetryClick(item.chapter.id) },
                )
            }
        }
    }
}

@Composable
private fun OcrQueueItemRow(
    item: OcrQueueItem,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(end = 8.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.manga.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.chapter.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val statusText = when (item.status) {
                OcrQueueStatus.PENDING -> stringResource(MR.strings.ocr_status_pending)
                OcrQueueStatus.WAITING_DOWNLOAD -> stringResource(MR.strings.ocr_waiting_download)
                OcrQueueStatus.PROCESSING -> {
                    if (item.totalPages > 0) {
                        "${item.currentPage}/${item.totalPages}"
                    } else {
                        stringResource(MR.strings.ocr_status_processing)
                    }
                }
                OcrQueueStatus.COMPLETED -> stringResource(MR.strings.ocr_ready)
                OcrQueueStatus.ERROR -> stringResource(MR.strings.ocr_status_error)
                OcrQueueStatus.CANCELLED -> stringResource(MR.strings.cancelled)
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = when (item.status) {
                    OcrQueueStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (item.status) {
                OcrQueueStatus.PENDING, OcrQueueStatus.WAITING_DOWNLOAD -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                OcrQueueStatus.PROCESSING -> {
                    CircularProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                }
                OcrQueueStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                OcrQueueStatus.ERROR -> {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = stringResource(MR.strings.action_retry),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                OcrQueueStatus.CANCELLED -> {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (item.status in
            listOf(
                OcrQueueStatus.PENDING,
                OcrQueueStatus.WAITING_DOWNLOAD,
                OcrQueueStatus.PROCESSING,
                OcrQueueStatus.ERROR,
            )
        ) {
            IconButton(onClick = onCancelClick) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_cancel),
                )
            }
        }
    }
}
