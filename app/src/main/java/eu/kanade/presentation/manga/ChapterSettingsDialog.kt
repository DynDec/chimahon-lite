package eu.kanade.presentation.manga

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ChapterSettingsDialog(
    onDismissRequest: () -> Unit,
    manga: Manga? = null,
    onUnreadFilterChanged: (TriState) -> Unit,
    onBookmarkedFilterChanged: (TriState) -> Unit,
    onSortModeChanged: (Long) -> Unit,
    onDisplayModeChanged: (Long) -> Unit,
    // Chimahon -->
    onLayoutChanged: (Long) -> Unit,
    showsGrid: Boolean,
    // Chimahon <--
    onSetAsDefault: (applyToExistingManga: Boolean) -> Unit,
    onResetToDefault: () -> Unit,
) {
    var showSetAsDefaultDialog by rememberSaveable { mutableStateOf(false) }
    if (showSetAsDefaultDialog) {
        SetAsDefaultDialog(
            onDismissRequest = { showSetAsDefaultDialog = false },
            onConfirmed = onSetAsDefault,
        )
    }

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.set_chapter_settings_as_default)) },
                onClick = {
                    showSetAsDefaultDialog = true
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onResetToDefault()
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    FilterPage(
                        unreadFilter = manga?.unreadFilter ?: TriState.DISABLED,
                        onUnreadFilterChanged = onUnreadFilterChanged,
                        bookmarkedFilter = manga?.bookmarkedFilter ?: TriState.DISABLED,
                        onBookmarkedFilterChanged = onBookmarkedFilterChanged,
                    )
                }
                1 -> {
                    SortPage(
                        sortingMode = manga?.sorting ?: Manga.CHAPTER_SORTING_NUMBER,
                        sortDescending = manga?.sortDescending() ?: false,
                        onItemSelected = onSortModeChanged,
                    )
                }
                2 -> {
                    DisplayPage(
                        displayMode = manga?.displayMode ?: 0,
                        onItemSelected = onDisplayModeChanged,
                        // Chimahon -->
                        layout = manga?.chapterLayout ?: Manga.CHAPTER_LAYOUT_AUTO,
                        onLayoutSelected = onLayoutChanged,
                        showsGrid = showsGrid,
                        // Chimahon <--
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    unreadFilter: TriState,
    onUnreadFilterChanged: (TriState) -> Unit,
    bookmarkedFilter: TriState,
    onBookmarkedFilterChanged: (TriState) -> Unit,
) {
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = unreadFilter,
        onClick = onUnreadFilterChanged,
    )
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = bookmarkedFilter,
        onClick = onBookmarkedFilterChanged,
    )
}

@Composable
private fun ColumnScope.SortPage(
    sortingMode: Long,
    sortDescending: Boolean,
    onItemSelected: (Long) -> Unit,
) {
    listOf(
        MR.strings.sort_by_number to Manga.CHAPTER_SORTING_NUMBER,
        KMR.strings.sort_by_chapter_date to Manga.CHAPTER_SORTING_UPLOAD_DATE,
        MR.strings.action_sort_alpha to Manga.CHAPTER_SORTING_ALPHABET,
    ).map { (titleRes, mode) ->
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = { onItemSelected(mode) },
        )
    }
    Text(
        text = stringResource(KMR.strings.chapter_date_summary),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ColumnScope.DisplayPage(
    displayMode: Long,
    onItemSelected: (Long) -> Unit,
    // Chimahon -->
    layout: Long,
    onLayoutSelected: (Long) -> Unit,
    showsGrid: Boolean,
    // Chimahon <--
) {
    // Chimahon -->
    listOf(
        MR.strings.label_auto to Manga.CHAPTER_LAYOUT_AUTO,
        MR.strings.chapter_layout_grid to Manga.CHAPTER_LAYOUT_GRID,
        MR.strings.action_display_list to Manga.CHAPTER_LAYOUT_LIST,
    ).map { (titleRes, mode) ->
        RadioItem(
            label = stringResource(titleRes),
            selected = layout == mode,
            onClick = { onLayoutSelected(mode) },
        )
    }
    if (showsGrid) {
        val configuration = LocalConfiguration.current
        val columnPreference = remember(configuration.orientation) {
            val preferences = Injekt.get<LibraryPreferences>()
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                preferences.volumeLandscapeColumns()
            } else {
                preferences.volumePortraitColumns()
            }
        }
        val columns by columnPreference.collectAsState()
        SliderItem(
            value = columns,
            valueRange = 0..10,
            label = stringResource(MR.strings.pref_library_columns),
            valueString = if (columns > 0) columns.toString() else stringResource(MR.strings.label_auto),
            onChange = columnPreference::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        CheckboxItem(
            label = stringResource(MR.strings.pref_single_volume_row),
            pref = remember { Injekt.get<LibraryPreferences>().singleVolumeRow() },
        )
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    // Chimahon <--
    listOf(
        MR.strings.show_title to Manga.CHAPTER_DISPLAY_NAME,
        MR.strings.show_chapter_number to Manga.CHAPTER_DISPLAY_NUMBER,
    ).map { (titleRes, mode) ->
        RadioItem(
            label = stringResource(titleRes),
            selected = displayMode == mode,
            onClick = { onItemSelected(mode) },
        )
    }
}

@Composable
private fun SetAsDefaultDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: (optionalChecked: Boolean) -> Unit,
) {
    var optionalChecked by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.chapter_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(MR.strings.confirm_set_chapter_settings))

                LabeledCheckbox(
                    label = stringResource(MR.strings.also_set_chapter_settings_for_library),
                    checked = optionalChecked,
                    onCheckedChange = { optionalChecked = it },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmed(optionalChecked)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
