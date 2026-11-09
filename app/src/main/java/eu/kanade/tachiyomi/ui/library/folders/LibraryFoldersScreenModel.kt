package eu.kanade.tachiyomi.ui.library.folders

import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.hippo.unifile.UniFile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.storage.areSameFileUris
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.storage.service.LibraryFolder
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryFoldersScreenModel(
    private val storagePreferences: StoragePreferences = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    getCategories: GetCategories = Injekt.get(),
) : StateScreenModel<LibraryFoldersScreenModel.State>(State()) {

    /** [categoryId] is 0 for no category. */
    @Immutable
    data class Folder(
        val uri: String,
        val name: String,
        val categoryId: Long,
        val readable: Boolean,
        val isMangaFolder: Boolean,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val folders: ImmutableList<Folder> = persistentListOf(),
        val categories: ImmutableList<Category> = persistentListOf(),
    )

    sealed interface Event {
        data object FolderExists : Event
    }

    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            combine(
                // The manga folder is resolved by the storage manager after its preference changes.
                storageManager.changes.onStart { emit(Unit) },
                storagePreferences.mangaDirectoryCategory().changes(),
                storagePreferences.libraryFolders().changes(),
                getCategories.subscribe(),
            ) { _, mangaFolderCategory, libraryFolders, categories ->
                val mangaFolder = storageManager.getLocalSourceDirectory()
                val mangaFolderUri = mangaFolder?.uri?.toString()
                val folders = buildList {
                    if (mangaFolder != null) {
                        add(folder(mangaFolderUri!!, mangaFolder, mangaFolderCategory, isMangaFolder = true))
                    }
                    libraryFolders
                        .filter { mangaFolderUri == null || !areSameFileUris(it.uri, mangaFolderUri) }
                        .forEach { add(folder(it.uri, storageManager.getFileFromUri(it.uri), it.categoryId, false)) }
                }
                State(
                    isLoading = false,
                    folders = folders.toImmutableList(),
                    categories = categories.filterNot(Category::isSystemCategory).toImmutableList(),
                )
            }
                .collectLatest { state -> mutableState.update { state } }
        }
    }

    private fun folder(uri: String, directory: UniFile?, categoryId: Long, isMangaFolder: Boolean): Folder {
        val readable = directory != null && directory.isDirectory && directory.canRead()
        return Folder(
            uri = uri,
            name = directory?.takeIf { readable }?.name
                ?: uri.toUri().lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifEmpty { null }
                ?: uri,
            categoryId = categoryId,
            readable = readable,
            isMangaFolder = isMangaFolder,
        )
    }

    /** Adds the folder [uri], which must already have a persisted permission, with no category. */
    fun addFolder(uri: String) {
        val mangaFolder = storageManager.getLocalSourceDirectory()?.uri?.toString()
        val folders = storagePreferences.libraryFolders().get()
        if ((listOfNotNull(mangaFolder) + folders.map { it.uri }).any { areSameFileUris(uri, it) }) {
            _events.trySend(Event.FolderExists)
            return
        }
        storagePreferences.libraryFolders().set(folders + LibraryFolder(uri))
    }

    /** Manga already added from [folder] stay in the library. */
    fun removeFolder(folder: Folder) {
        val folders = storagePreferences.libraryFolders()
        folders.set(folders.get().filterNot { it.uri == folder.uri })
    }

    fun setCategory(folder: Folder, categoryId: Long) {
        if (folder.isMangaFolder) {
            storagePreferences.mangaDirectoryCategory().set(categoryId)
            return
        }
        val folders = storagePreferences.libraryFolders()
        folders.set(folders.get().map { if (it.uri == folder.uri) it.copy(categoryId = categoryId) else it })
    }
}
