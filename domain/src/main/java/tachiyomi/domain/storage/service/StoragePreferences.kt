package tachiyomi.domain.storage.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

class StoragePreferences(
    private val folderProvider: FolderProvider,
    private val preferenceStore: PreferenceStore,
) {

    // Stores the URI of the app-owned data directory (either file:/// or content://).
    fun baseStorageDirectory() = preferenceStore.getString(Preference.appStateKey("storage_dir"), folderProvider.path())

    // An optional user-selected directory scanned by the local manga source. An empty value
    // uses the configured base storage directory directly.
    fun mangaDirectory() = preferenceStore.getString(Preference.appStateKey("manga_dir"), "")

    fun showEpisodeFileSize() = preferenceStore.getBoolean(Preference.appStateKey("show_episode_file_size"), false)

    // Chimahon -->
    // Local folder URLs the library sync has seen listed. A listed folder outside this set is new and is added.
    fun localLibraryKnownFolders() =
        preferenceStore.getStringSet(Preference.appStateKey("local_library_known"), emptySet())

    // Local folder URLs the library sync took out because they stopped being listed, to add back when they return.
    fun localLibraryMissingFolders() =
        preferenceStore.getStringSet(Preference.appStateKey("local_library_missing"), emptySet())

    // The naming rules the stored local chapter names follow. The library sync lists chapters again when it is behind.
    fun localChapterNameVersion() = preferenceStore.getInt(Preference.appStateKey("local_chapter_name_version"), 0)

    // The category the library sync adds manga from the manga folder to; 0 adds them to no category.
    fun mangaDirectoryCategory() = preferenceStore.getLong(Preference.appStateKey("manga_dir_category"), 0L)

    // Folders besides the manga folder whose manga the library sync adds.
    fun libraryFolders() = preferenceStore.getObjectFromString(
        key = Preference.appStateKey("library_folders"),
        defaultValue = emptyList(),
        serializer = { folders -> folders.joinToString("\n") { "${it.categoryId}\t${it.uri}" } },
        deserializer = { value ->
            value.lineSequence()
                .mapNotNull { line ->
                    val categoryId = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                    LibraryFolder(uri = line.substringAfter('\t'), categoryId = categoryId)
                }
                .toList()
        },
    )
    // Chimahon <--
}

// Chimahon -->
/** A folder whose manga the library sync adds to the category [categoryId], or to no category when it is 0. */
data class LibraryFolder(val uri: String, val categoryId: Long = 0L)
// Chimahon <--
