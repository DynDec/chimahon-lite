package eu.kanade.tachiyomi.data.cache

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.interactor.UpdateManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCoverRecoveryTest {
    @TempDir
    lateinit var directory: File

    private val manager = mockk<LocalCoverManager>()
    private val cache = mockk<CoverCache>(relaxed = true)
    private val update = mockk<UpdateManga>(relaxed = true)
    private val source = mockk<LocalSource> { every { id } returns LocalSource.ID }
    private val coverUrl = "file:///data/user/0/app/cache/local_covers/cover-book.jpg"

    @Test
    fun `missing library cover is regenerated and refreshes timestamp even at the same URI`() = runTest {
        val manga = manga()
        val mangaUrl = manga.url
        val captured = slot<MangaUpdate>()
        every { manager.find(any()) } returnsMany listOf(null, cover(coverUrl))
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } returns mockk()
        coEvery { update.await(capture(captured)) } returns true

        assertTrue(recovery().recover(manga, source))

        coVerify(exactly = 1) {
            source.getMangaUpdate(match { it.url == mangaUrl }, emptyList(), false, true)
        }
        assertEquals(manga.id, captured.captured.id)
        assertEquals(coverUrl, captured.captured.thumbnailUrl)
        assertTrue(captured.captured.coverLastModified!! > manga.coverLastModified)
    }

    @Test
    fun `stale thumbnail is repaired from an existing cover without opening chapters`() = runTest {
        val manga = manga(thumbnail = "file:///old/cache/cover.jpg")
        every { manager.find(any()) } returns cover(coverUrl)

        assertTrue(recovery().recover(manga, source))

        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
        coVerify(exactly = 1) { update.await(match { it.thumbnailUrl == coverUrl }) }
    }

    @Test
    fun `healthy library covers are neither regenerated nor written back`() = runTest {
        val manga = manga()
        every { manager.find(any()) } returns cover(coverUrl)

        assertTrue(recovery().recover(manga, source))

        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
        coVerify(exactly = 0) { update.await(any()) }
    }

    @Test
    fun `unavailable books keep their saved thumbnail without a false successful repair`() = runTest {
        val manga = manga()
        every { manager.find(any()) } returns null
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } returns mockk()

        assertFalse(recovery().recover(manga, source))

        coVerify(exactly = 0) { update.await(any()) }
        verify(exactly = 0) { cache.deleteFromCache(any<Manga>(), any()) }
    }

    @Test
    fun `existing encrypted cover is repaired without changing its format`() = runTest {
        val manga = manga(thumbnail = null)
        val encryptedUrl = coverUrl.replace(".jpg", ".cbi")
        every { manager.find(any()) } returns cover(encryptedUrl)

        assertTrue(recovery().recover(manga, source))

        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
        coVerify(exactly = 1) { update.await(match { it.thumbnailUrl == encryptedUrl }) }
    }

    @Test
    fun `custom covers are preserved without extraction`() = runTest {
        val manga = manga()
        File(directory, "custom-${manga.id}").writeText("custom image")

        assertTrue(recovery().recover(manga, source))

        verify(exactly = 0) { manager.find(any()) }
        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
        coVerify(exactly = 0) { update.await(any()) }
    }

    @Test
    fun `library and browse requests for the same book only extract once`() = runTest {
        val manga = manga()
        var available: UniFile? = null
        every { manager.find(any()) } answers { available }
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            delay(10)
            available = cover(coverUrl)
            mockk()
        }
        val library = recovery()
        val browse = recovery()

        val results = listOf(async { library.recover(manga, source) }, async { browse.recover(manga, source) }).awaitAll()

        assertTrue(results.all { it })
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), any(), any()) }
    }

    @Test
    fun `archive concurrency is bounded across library and browse instances`() = runTest {
        val books = (1L..8L).map { manga(id = it) }
        val available = mutableMapOf<String, UniFile>()
        var active = 0
        var maximum = 0
        every { manager.find(any()) } answers { available[firstArg()] }
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            active++
            maximum = maxOf(maximum, active)
            delay(10)
            available[firstArg<eu.kanade.tachiyomi.source.model.SManga>().url] = cover(coverUrl)
            active--
            mockk()
        }
        val library = recovery()
        val browse = recovery()

        books.mapIndexed { index, book ->
            async { (if (index % 2 == 0) library else browse).recover(book, source) }
        }.awaitAll()

        assertEquals(2, maximum)
        coVerify(exactly = 8) { source.getMangaUpdate(any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation propagates and releases coordination for a later attempt`() = runTest {
        val manga = manga()
        every { manager.find(any()) } returns null
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } throws CancellationException()
        val recovery = recovery()

        val cancelled = async { recovery.recover(manga, source) }
        try {
            cancelled.await()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Retry below verifies that neither the per-book lock nor the global permit leaked.
        }
        coVerify(exactly = 0) { update.await(any()) }
        every { manager.find(any()) } returns cover(coverUrl)
        assertTrue(recovery.recover(manga, source))
    }

    private fun kotlinx.coroutines.test.TestScope.recovery() = LocalCoverRecovery(
        manager,
        cache,
        update,
        StandardTestDispatcher(testScheduler),
    )

    private fun manga(id: Long = 1, thumbnail: String? = coverUrl): Manga {
        every { cache.getCustomCoverFile(id) } returns File(directory, "custom-$id")
        return mockk(relaxed = true) {
            every { this@mockk.id } returns id
            every { source } returns LocalSource.ID
            every { url } returns "content://books/book-$id.epub"
            every { thumbnailUrl } returns thumbnail
            every { coverLastModified } returns 1L
        }
    }

    private fun cover(url: String): UniFile {
        val uri = mockk<Uri>()
        every { uri.toString() } returns url
        val file = mockk<UniFile>()
        every { file.uri } returns uri
        return file
    }
}
