package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.domain.library.service.LibraryPreferences

class RemoveObsoleteMangaFiltersMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val preferences = migrationContext.get<LibraryPreferences>() ?: return false
        migrate(preferences)
        return true
    }

    internal fun migrate(preferences: LibraryPreferences) {
        preferences.filterDownloaded().delete()
        preferences.filterIntervalCustom().delete()
        preferences.filterChapterByDownloaded().delete()
        preferences.downloadBadge().delete()
    }
}
