package eu.kanade.tachiyomi.ui.category

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryContent
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class CategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CategoryScreenModel() }
        val state by screenModel.state.collectAsState()
        val fabLazyListState = rememberLazyListState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.categories),
                    navigateUp = navigator::pop,
                )
            },
            floatingActionButton = {
                CategoryFloatingActionButton(
                    lazyListState = fabLazyListState,
                    onCreate = { screenModel.showDialog(CategoryDialog.Create) },
                )
            },
        ) { paddingValues ->
            MangaTab(
                state = state,
                screenModel = screenModel,
                contentPadding = paddingValues,
            )
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                if (event is CategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }
}

@Composable
private fun MangaTab(
    state: CategoryScreenState,
    screenModel: CategoryScreenModel,
    contentPadding: PaddingValues,
) {
    if (state is CategoryScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as CategoryScreenState.Success

    if (successState.isEmpty) {
        EmptyScreen(
            stringRes = MR.strings.information_empty_category,
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        val lazyListState = rememberLazyListState()
        CategoryContent(
            categories = successState.categories,
            lazyListState = lazyListState,
            paddingValues = contentPadding,
            onClickRename = { screenModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(CategoryDialog.Delete(it)) },
            onChangeOrder = screenModel::changeOrder,
            onClickHide = { screenModel.hideCategory(it) },
        )
    }

    when (val dialog = successState.dialog) {
        null -> {}
        CategoryDialog.Create -> {
            CategoryCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = screenModel::createCategory,
                categories = successState.categories.fastMap { it.name }.toImmutableList(),
            )
        }
        is CategoryDialog.Rename -> {
            CategoryRenameDialog(
                onDismissRequest = screenModel::dismissDialog,
                onRename = { screenModel.renameCategory(dialog.category, it) },
                categories = successState.categories.fastMap { it.name }.toImmutableList(),
                category = dialog.category.name,
            )
        }
        is CategoryDialog.Delete -> {
            CategoryDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.deleteCategory(dialog.category.id) },
                category = dialog.category.name,
            )
        }
    }
}
