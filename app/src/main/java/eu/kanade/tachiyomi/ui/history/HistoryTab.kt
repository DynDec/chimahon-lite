package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.history.HistoryScreenContent
import eu.kanade.presentation.history.HistorySelectionToolbar
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.history.components.HistoryFilterDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.SearchHistory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.collectAsState

data object HistoryTab : Tab {
    @Suppress("unused")
    private fun readResolve(): Any = HistoryTab

    private val snackbarHostState = SnackbarHostState()
    private val resumeLastMediaEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastMediaEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { HistoryScreenModel() }
        val state by screenModel.state.collectAsState()
        val settingsScreenModel = rememberScreenModel { HistorySettingsScreenModel() }
        val usePanoramaCover by settingsScreenModel.historyPreferences.usePanoramaCover().collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                if (state.selectionMode) {
                    HistorySelectionToolbar(
                        selectedCount = state.selection.size,
                        onCancelActionMode = screenModel::toggleSelectionMode,
                        onClickSelectAll = { screenModel.toggleAllSelection(true) },
                        onClickInvertSelection = screenModel::invertSelection,
                        onClickClearHistory = { screenModel.setDialog(HistoryScreenModel.Dialog.Delete(state.selected)) },
                    )
                } else {
                    SearchToolbar(
                        titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
                        searchQuery = state.searchQuery,
                        onChangeSearchQuery = screenModel::updateSearchQuery,
                        searchHistoryScope = SearchHistory.SCOPE_ANIME_MANGA,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    eu.kanade.presentation.components.AppBar.Action(
                                        title = stringResource(MR.strings.action_filter),
                                        icon = Icons.Outlined.FilterList,
                                        iconTint = if (state.hasActiveFilters) {
                                            MaterialTheme.colorScheme.active
                                        } else {
                                            LocalContentColor.current
                                        },
                                        onClick = screenModel::showFilterDialog,
                                    ),
                                    eu.kanade.presentation.components.AppBar.Action(
                                        title = stringResource(MR.strings.pref_clear_history),
                                        icon = Icons.Outlined.Checklist,
                                        onClick = screenModel::toggleSelectionMode,
                                    ),
                                ),
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            snackbarHost = { androidx.compose.material3.SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.list.isEmpty() -> {
                    val message = if (!state.searchQuery.isNullOrEmpty()) {
                        MR.strings.no_results_found
                    } else {
                        MR.strings.information_no_recent_manga
                    }
                    EmptyScreen(
                        stringRes = message,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
                else -> {
                    val uiModels = remember(state.list) { state.getUiModel() }
                    HistoryScreenContent(
                        state = state,
                        history = uiModels,
                        contentPadding = contentPadding,
                        onClickCover = { history -> navigator.push(MangaScreen(history.mangaId)) },
                        onClickResume = { history ->
                            screenModel.getNextChapterForManga(history.mangaId, history.chapterId)
                        },
                        onClickDelete = { item -> screenModel.setDialog(HistoryScreenModel.Dialog.Delete(item)) },
                        onClickFavorite = { history -> screenModel.addFavorite(history.mangaId) },
                        selectionMode = state.selectionMode,
                        onHistorySelected = screenModel::toggleSelection,
                        usePanoramaCover = usePanoramaCover,
                    )
                }
            }
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is HistoryScreenModel.Dialog.Delete -> {
                HistoryDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = { all ->
                        if (all) {
                            screenModel.removeAllFromHistory(dialog.histories)
                        } else {
                            screenModel.removeFromHistory(dialog.histories)
                        }
                    },
                )
            }
            is HistoryScreenModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = screenModel::removeAllHistory,
                )
            }
            is HistoryScreenModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.showMigrateDialog(dialog.manga, it) },
                    targetManga = dialog.manga,
                )
            }
            is HistoryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is HistoryScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is HistoryScreenModel.Dialog.FilterSheet -> {
                HistoryFilterDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                )
            }
            null -> Unit
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
                with(DiscordRPCService) {
                    discordScope.launchIO { setScreen(context, DiscordScreen.HISTORY) }
                }
            }
        }

        LaunchedEffect(Unit) {
            launch {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        HistoryScreenModel.Event.InternalError ->
                            snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                        HistoryScreenModel.Event.HistoryCleared ->
                            snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                        is HistoryScreenModel.Event.OpenChapter -> openChapter(context, event.chapter)
                    }
                }
            }
            launch {
                resumeLastMediaEvent.receiveAsFlow().collectLatest {
                    val history = screenModel.getLastHistory()
                    if (history == null) {
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                    } else {
                        openChapter(context, screenModel.getNextChapter(history.mangaId, history.chapterId))
                    }
                }
            }
        }
    }

    private suspend fun openChapter(context: Context, chapter: Chapter?) {
        if (chapter != null) {
            context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
        } else {
            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
        }
    }
}
