package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.interactor.RelinkLocalManga
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class RelinkFolderScreen(private val mangaId: Long) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { Model(mangaId, RelinkLocalManga(), context.applicationContext) }
        val state by model.state.collectAsState()
        var confirmUnchanged by remember(state.preview) { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    model.preview(uri.toString())
                } catch (e: Exception) {
                    model.error(context.stringResource(MR.strings.relink_folder_unavailable))
                }
            }
        }
        BackHandler(state.applying) { /* Finish the atomic relink before leaving. */ }
        LaunchedEffect(state.done) { if (state.done) navigator.pop() }
        Scaffold(
            topBar = { AppBar(title = stringResource(MR.strings.action_relink_folder), navigateUp = { if (!state.applying) navigator.pop() }, scrollBehavior = it) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(stringResource(MR.strings.relink_folder_description))
                    Button(onClick = { picker.launch(null) }, enabled = !state.busy) {
                        Text(stringResource(MR.strings.relink_folder_choose))
                    }
                }
                if (state.busy) item { CircularProgressIndicator() }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                state.preview?.let { preview ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(preview.folderName, style = MaterialTheme.typography.titleMedium)
                            Text(preview.folderUrl, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(MR.strings.relink_folder_summary, preview.matchedCount, preview.retainedCount, preview.addedCount))
                            Text(stringResource(MR.strings.relink_folder_retained_description))
                            if (preview.unverifiedCount > 0) {
                                Text(stringResource(MR.strings.relink_folder_unverified, preview.unverifiedCount))
                                Row {
                                    Checkbox(checked = confirmUnchanged, onCheckedChange = { confirmUnchanged = it }, enabled = !state.busy)
                                    Text(stringResource(MR.strings.relink_folder_confirm_unchanged), modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                            Button(
                                onClick = { model.apply(confirmUnchanged) },
                                enabled = !state.busy && (preview.unverifiedCount == 0 || confirmUnchanged),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(MR.strings.action_relink_folder)) }
                        }
                    }
                    item { Text(stringResource(MR.strings.relink_folder_matched), style = MaterialTheme.typography.titleSmall) }
                    items(preview.matchedNames) { Text(it) }
                    item { Text(stringResource(MR.strings.relink_folder_retained), style = MaterialTheme.typography.titleSmall) }
                    items(preview.retainedNames) { Text(it) }
                    item { Text(stringResource(MR.strings.relink_folder_added), style = MaterialTheme.typography.titleSmall) }
                    items(preview.addedNames) { Text(it) }
                }
            }
        }
    }

    private class Model(private val mangaId: Long, private val relink: RelinkLocalManga, private val context: Context) : StateScreenModel<Model.State>(State()) {
        data class State(val preview: RelinkLocalManga.Preview? = null, val busy: Boolean = false, val applying: Boolean = false, val error: String? = null, val done: Boolean = false)
        fun error(message: String) = mutableState.update { it.copy(error = message, preview = null) }
        fun preview(url: String) = run {
            mutableState.update { it.copy(preview = null) }
            val preview = relink.preview(mangaId, url)
            mutableState.update { it.copy(preview = preview) }
        }
        fun apply(confirmUnchanged: Boolean) = run {
            mutableState.update { it.copy(applying = true) }
            val preview = state.value.preview ?: return@run
            relink.apply(preview, confirmUnchanged)
            mutableState.update { it.copy(done = true) }
        }
        private fun run(action: suspend () -> Unit) {
            if (state.value.busy) return
            mutableState.update { it.copy(busy = true, error = null) }
            screenModelScope.launch {
                try {
                    action()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val resource = when ((e as? RelinkLocalManga.RelinkException)?.reason) {
                        RelinkLocalManga.Error.AlreadyLinked -> MR.strings.relink_folder_already_linked
                        RelinkLocalManga.Error.SameFolder -> MR.strings.relink_folder_same
                        RelinkLocalManga.Error.EmptyFolder -> MR.strings.relink_folder_empty
                        RelinkLocalManga.Error.OcrBusy -> MR.strings.relink_folder_ocr_busy
                        RelinkLocalManga.Error.Changed -> MR.strings.relink_folder_changed
                        else -> MR.strings.relink_folder_unavailable
                    }
                    mutableState.update { it.copy(error = context.stringResource(resource)) }
                } finally {
                    mutableState.update { it.copy(busy = false, applying = false) }
                }
            }
        }
    }
}
