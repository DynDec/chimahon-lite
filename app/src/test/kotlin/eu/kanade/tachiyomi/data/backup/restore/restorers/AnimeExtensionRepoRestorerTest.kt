package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.animeextensionrepo.interactor.GetAnimeExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Test
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import kotlin.test.assertFailsWith

class AnimeExtensionRepoRestorerTest {

    private val animeHandler = mockk<AnimeDatabaseHandler>(relaxed = true)
    private val getExtensionRepos = mockk<GetAnimeExtensionRepo>()
    private val restorer = AnimeExtensionRepoRestorer(animeHandler, getExtensionRepos)

    @Test
    fun `restoring an already installed repository is idempotent`() = runTest {
        coEvery { getExtensionRepos.getAll() } returns listOf(repo())

        restorer(backupRepo())

        coVerify(exactly = 1) { getExtensionRepos.getAll() }
    }

    @Test
    fun `same URL with a different fingerprint remains rejected`() = runTest {
        coEvery { getExtensionRepos.getAll() } returns listOf(repo(fingerprint = "installed"))

        assertFailsWith<IllegalStateException> {
            restorer(backupRepo(fingerprint = "backup"))
        }
    }

    @Test
    fun `same fingerprint at a different URL remains rejected`() = runTest {
        coEvery { getExtensionRepos.getAll() } returns listOf(repo(baseUrl = "https://installed.example"))

        assertFailsWith<IllegalStateException> {
            restorer(backupRepo(baseUrl = "https://backup.example"))
        }
    }

    private fun repo(
        baseUrl: String = "https://repo.example",
        fingerprint: String = "fingerprint",
    ) = ExtensionRepo(
        baseUrl = baseUrl,
        name = "Repository",
        shortName = "Repo",
        website = "https://example.com",
        signingKeyFingerprint = fingerprint,
    )

    private fun backupRepo(
        baseUrl: String = "https://repo.example",
        fingerprint: String = "fingerprint",
    ) = BackupExtensionRepos(
        baseUrl = baseUrl,
        name = "Repository",
        shortName = "Repo",
        website = "https://example.com",
        signingKeyFingerprint = fingerprint,
    )
}
