package eu.kanade.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.library.folders.LibraryFoldersScreenModel.Folder
import eu.kanade.tachiyomi.ui.library.folders.LibraryFoldersScreenModel.State
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB

@Composable
fun LibraryFoldersScreen(
    state: State,
    onClickAdd: () -> Unit,
    onClickFolder: (Folder) -> Unit,
    onClickRemove: (Folder) -> Unit,
    navigateUp: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.library_folders),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                text = { Text(text = stringResource(MR.strings.library_folders_add)) },
                icon = { Icon(imageVector = Icons.Outlined.Add, contentDescription = null) },
                onClick = onClickAdd,
                expanded = lazyListState.shouldExpandFAB(),
            )
        },
    ) { paddingValues ->
        if (state.isLoading) {
            LoadingScreen(Modifier.padding(paddingValues))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = paddingValues +
                topSmallPaddingValues +
                PaddingValues(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items(items = state.folders, key = { it.uri }) { folder ->
                LibraryFolderItem(
                    folder = folder,
                    categoryName = categoryName(folder.categoryId, state.categories),
                    onClick = { onClickFolder(folder) },
                    onRemove = { onClickRemove(folder) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun LibraryFolderItem(
    folder: Folder,
    categoryName: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .minimumInteractiveComponentSize()
                .padding(vertical = MaterialTheme.padding.small)
                .padding(start = MaterialTheme.padding.small, end = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (folder.isMangaFolder) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.padding(MaterialTheme.padding.medium),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (folder.readable) categoryName else stringResource(MR.strings.library_folder_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (folder.readable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (!folder.isMangaFolder) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.action_remove),
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryFolderCategoryDialog(
    folder: Folder,
    categories: ImmutableList<Category>,
    onDismissRequest: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val entries = listOf(0L to stringResource(MR.strings.label_default)) + categories.map { it.id to it.name }
    // A deleted category means none.
    val selectedId = folder.categoryId.takeIf { id -> categories.any { it.id == id } } ?: 0L
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = folder.name) },
        text = {
            LazyColumn {
                items(items = entries, key = { it.first }) { (id, name) ->
                    val isSelected = id == selectedId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = isSelected, onClick = { onSelect(id) })
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize(),
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = MaterialTheme.padding.large),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/** The category manga from a folder go to; a deleted category means none. */
@Composable
private fun categoryName(categoryId: Long, categories: ImmutableList<Category>): String {
    return categories.firstOrNull { it.id == categoryId }?.name ?: stringResource(MR.strings.label_default)
}
