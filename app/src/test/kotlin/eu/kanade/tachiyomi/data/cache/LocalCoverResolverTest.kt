package eu.kanade.tachiyomi.data.cache

import eu.kanade.domain.manga.interactor.UpdateManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCoverResolverTest {
    @TempDir
    lateinit var directory: File

    private val manager = mockk<LocalCoverManager>()
    private val cache = mockk<CoverCache>()
    private val update = mockk<UpdateManga>()
    private val source = mockk<LocalSource>()
    private val reference = "chimahon-cover://archive?file=content%3A%2F%2Fbook.zip&entry=001.jpg&v=2"
    private val page = byteArrayOf(1, 2, 3, 4)
    private lateinit var custom: File

    @BeforeEach
    fun setup() {
        custom = File(directory, "custom/5")
        every { cache.getCustomCoverFile(5) } returns custom
        every { manager.read(reference) } returns page
        coEvery { update.await(any()) } returns true
    }

    @Test
    fun `entries with a cover reference, folder cover or custom URL are left alone`() {
        val resolver = resolver(StandardTestDispatcher())
        for (url in listOf(reference, "content://tree/document/cover.jpg", "https://example.com/c.jpg")) {
            assertFalse(resolver.needsResolution(manga(url)))
        }
        assertFalse(resolver.needsResolution(manga(null).copy(source = 1)))
        assertTrue(resolver.needsResolution(manga(null)))
        assertTrue(resolver.needsResolution(manga("file:///data/cache/local_covers/cover-abc.jpg")))
        // References chosen by an earlier cover selection are resolved again.
        assertTrue(resolver.needsResolution(manga(reference.removeSuffix("&v=2"))))
    }

    @Test
    fun `missing cover is resolved to a reference without extracting anything`() = runTest {
        every { source.resolveCover("content://book") } returns reference
        val captured = slot<MangaUpdate>()
        coEvery { update.await(capture(captured)) } returns true
        val manga = manga(null)

        assertTrue(resolver(StandardTestDispatcher(testScheduler)).resolve(manga, source))

        assertEquals(reference, captured.captured.thumbnailUrl)
        assertTrue(captured.captured.coverLastModified!! > manga.coverLastModified)
        verify(exactly = 0) { manager.read(any()) }
    }

    @Test
    fun `unavailable books keep their state`() = runTest {
        every { source.resolveCover(any()) } returns null

        assertFalse(resolver(StandardTestDispatcher(testScheduler)).resolve(manga(null), source))
        coVerify(exactly = 0) { update.await(any()) }
    }

    @Test
    fun `legacy generated cover matching the automatic cover is deleted`() {
        val legacy = legacy("jpg", page)

        resolver(StandardTestDispatcher()).retireLegacyCover(manga(legacy.toURI().toString()), reference)

        assertFalse(legacy.exists())
        assertFalse(custom.exists())
    }

    @Test
    fun `legacy cover the user picked becomes the custom cover`() {
        val picked = byteArrayOf(9, 9, 9)
        val legacy = legacy("jpg", picked)

        resolver(StandardTestDispatcher()).retireLegacyCover(manga(legacy.toURI().toString()), reference)

        assertFalse(legacy.exists())
        assertArrayEquals(picked, custom.readBytes())
    }

    @Test
    fun `an existing custom cover and encrypted legacy covers are never overwritten or kept`() {
        custom.parentFile!!.mkdirs()
        custom.writeBytes(byteArrayOf(7))
        val differing = legacy("jpg", byteArrayOf(9))
        resolver(StandardTestDispatcher()).retireLegacyCover(manga(differing.toURI().toString()), reference)
        assertArrayEquals(byteArrayOf(7), custom.readBytes())
        assertFalse(differing.exists())

        custom.delete()
        val encrypted = legacy("cbi", byteArrayOf(9))
        resolver(StandardTestDispatcher()).retireLegacyCover(manga(encrypted.toURI().toString()), reference)
        assertFalse(encrypted.exists())
        assertFalse(custom.exists())
    }

    @Test
    fun `unreadable automatic cover keeps the legacy image rather than losing a user choice`() {
        every { manager.read(reference) } returns null
        val legacy = legacy("jpg", page)

        resolver(StandardTestDispatcher()).retireLegacyCover(manga(legacy.toURI().toString()), reference)

        assertArrayEquals(page, custom.readBytes())
    }

    private fun legacy(extension: String, bytes: ByteArray) =
        File(directory, "local_covers/cover-abc.$extension").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    private fun manga(thumbnail: String?) = Manga.create().copy(
        id = 5,
        source = LocalSource.ID,
        url = "content://book",
        ogThumbnailUrl = thumbnail,
    )

    private fun resolver(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        LocalCoverResolver(manager, cache, update, mockk(), dispatcher)
}
