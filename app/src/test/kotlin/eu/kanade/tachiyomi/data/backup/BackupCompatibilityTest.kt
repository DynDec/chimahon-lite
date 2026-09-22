package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

class BackupCompatibilityTest {

    @Test
    fun `backups containing fields removed from creation still decode for restore`() {
        val oldBackup = PreviousBackup(
            sourcePreferences = listOf(BackupSourcePreferences("source_123", emptyList())),
            extensionStores = listOf(
                BackupExtensionStore(
                    indexUrl = "https://example.com/index.json",
                    name = "Old store",
                    badgeLabel = null,
                    signingKey = "key",
                    contactWebsite = "https://example.com",
                    contactDiscord = null,
                    isLegacy = false,
                    extensionListUrl = null,
                ),
            ),
            anime = listOf(BackupAnime(source = 123, url = "/anime", title = "Old anime")),
            novels = listOf(BackupNovel(id = "novel", title = "Old novel")),
        )
        val bytes = ProtoBuf.encodeToByteArray(PreviousBackup.serializer(), oldBackup)

        BackupDetector.isLegacyBackup(bytes).shouldBeFalse()
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        decoded.backupAnime shouldBe oldBackup.anime
        decoded.backupNovels shouldBe oldBackup.novels
        decoded.backupSourcePreferences shouldBe oldBackup.sourcePreferences
        decoded.backupExtensionStores.single().indexUrl shouldBe oldBackup.extensionStores.single().indexUrl

        val restoreOptions = RestoreOptions.fromBooleanArray(RestoreOptions().asBooleanArray())
        restoreOptions.animeEntries.shouldBeTrue()
        restoreOptions.novels.shouldBeTrue()
        restoreOptions.extensionStores.shouldBeTrue()
        restoreOptions.sourceSettings.shouldBeTrue()
    }

    // Pin the existing wire field numbers independently of the current Backup model.
    @Serializable
    private data class PreviousBackup(
        @ProtoNumber(105) val sourcePreferences: List<BackupSourcePreferences>,
        @ProtoNumber(106) val extensionStores: List<BackupExtensionStore>,
        @ProtoNumber(501) val anime: List<BackupAnime>,
        @ProtoNumber(700) val novels: List<BackupNovel>,
    )
}
