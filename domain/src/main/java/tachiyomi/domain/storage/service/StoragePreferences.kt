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
}
