package mihon.core.migration.migrations

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyAll
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences

class RemoveObsoleteMangaFiltersMigrationTest {

    @Test
    fun removesOnlyRetiredPreferences() {
        // Strict mocks reject any access to unrelated library/chapter preferences.
        val preferences = mockk<LibraryPreferences>()
        val downloaded = mockk<Preference<TriState>>(relaxed = true)
        val interval = mockk<Preference<TriState>>(relaxed = true)
        val chapterDownloaded = mockk<Preference<Long>>(relaxed = true)
        val badge = mockk<Preference<Boolean>>(relaxed = true)
        every { preferences.filterDownloaded() } returns downloaded
        every { preferences.filterIntervalCustom() } returns interval
        every { preferences.filterChapterByDownloaded() } returns chapterDownloaded
        every { preferences.downloadBadge() } returns badge

        RemoveObsoleteMangaFiltersMigration().migrate(preferences)

        verifyAll {
            preferences.filterDownloaded()
            preferences.filterIntervalCustom()
            preferences.filterChapterByDownloaded()
            preferences.downloadBadge()
            downloaded.delete()
            interval.delete()
            chapterDownloaded.delete()
            badge.delete()
        }
    }
}
