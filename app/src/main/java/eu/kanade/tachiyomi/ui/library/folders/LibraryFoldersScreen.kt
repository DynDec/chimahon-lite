package eu.kanade.tachiyomi.ui.library.folders

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.library.LibraryFolderCategoryDialog
import eu.kanade.presentation.library.LibraryFoldersScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

class LibraryFoldersScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { LibraryFoldersScreenModel() }
        val state by screenModel.state.collectAsState()
        var editing by remember { mutableStateOf<LibraryFoldersScreenModel.Folder?>(null) }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            // Kept after the folder is removed, so manga added from it can still be read.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                logcat(LogPriority.ERROR, e)
                context.toast(MR.strings.file_picker_uri_permission_unsupported)
            }
            UniFile.fromUri(context, uri)?.let { screenModel.addFolder(it.uri.toString()) }
        }

        LibraryFoldersScreen(
            state = state,
            onClickAdd = {
                try {
                    pickFolder.launch(null)
                } catch (_: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
            onClickFolder = { editing = it },
            onClickRemove = screenModel::removeFolder,
            navigateUp = navigator::pop,
        )

        editing?.let { folder ->
            LibraryFolderCategoryDialog(
                folder = folder,
                categories = state.categories,
                onDismissRequest = { editing = null },
                onSelect = { categoryId ->
                    screenModel.setCategory(folder, categoryId)
                    editing = null
                },
            )
        }

        LaunchedEffect(Unit) {
            screenModel.events.collect { event ->
                when (event) {
                    LibraryFoldersScreenModel.Event.FolderExists -> context.toast(MR.strings.library_folder_exists)
                }
            }
        }
    }
}
