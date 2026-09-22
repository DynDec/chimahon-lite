package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

class BackupOptionsTest {

    @Test
    fun `default backups exclude unsupported content`() {
        assertUnsupportedOptionsDisabled(BackupOptions())
        val labels = (BackupOptions.libraryOptions + BackupOptions.settingsOptions).map { it.label }
        labels.intersect(
            setOf(
                MR.strings.label_anime,
                MR.strings.backup_option_novels,
                MR.strings.extensionStores,
                MR.strings.source_settings,
            ),
        ).isEmpty() shouldBe true
    }

    @Test
    fun `older queued jobs cannot enable unsupported exports`() {
        for (size in listOf(12, 13, 14)) {
            val options = BackupOptions.fromBooleanArray(BooleanArray(size) { true })

            assertUnsupportedOptionsDisabled(options)
            options.privateSettings shouldBe true
            options.customInfo shouldBe true
            options.savedSearchesFeeds shouldBe true
        }
    }

    @Test
    fun `job options preserve supported positions and clear unsupported flags`() {
        val options = BackupOptions(
            categories = false,
            tracking = false,
            readEntries = false,
            appSettings = false,
            privateSettings = true,
            savedSearchesFeeds = false,
            animeEntries = true,
            novels = true,
            extensionStores = true,
            sourceSettings = true,
        )

        options.asBooleanArray().toList() shouldBe listOf(
            true, false, true, false, true, false, false,
            false, false, true, true, false, false, false,
        )
        BackupOptions.fromBooleanArray(options.asBooleanArray()) shouldBe options.copy(
            animeEntries = false,
            novels = false,
            extensionStores = false,
            sourceSettings = false,
        )
    }

    @Test
    fun `deselecting all visible options disables creation even with legacy flags`() {
        val legacyOptions = BackupOptions(
            animeEntries = true,
            novels = true,
            extensionStores = true,
            sourceSettings = true,
        )
        val deselected = (BackupOptions.libraryOptions + BackupOptions.settingsOptions)
            .fold(legacyOptions) { options, entry -> entry.setter(options, false) }

        deselected.canCreate().shouldBeFalse()
        // Hidden flags cannot enable manga details or private app settings.
        val mangaDisabled = legacyOptions.copy(libraryEntries = false, appSettings = false)
        BackupOptions.libraryOptions.first { it.label == MR.strings.chapters }
            .enabled(mangaDisabled).shouldBeFalse()
        BackupOptions.settingsOptions.first { it.label == MR.strings.private_settings }
            .enabled(mangaDisabled).shouldBeFalse()
    }

    private fun assertUnsupportedOptionsDisabled(options: BackupOptions) {
        options.animeEntries.shouldBeFalse()
        options.novels.shouldBeFalse()
        options.extensionStores.shouldBeFalse()
        options.sourceSettings.shouldBeFalse()
    }
}
