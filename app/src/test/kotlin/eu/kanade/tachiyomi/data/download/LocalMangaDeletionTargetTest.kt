package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem

class LocalMangaDeletionTargetTest {
    private val fileSystem = mockk<LocalSourceFileSystem>()
    private val provider = DownloadProvider(mockk<Context>(), mockk(), mockk(), mockk(), fileSystem)
    private val localSource = mockk<Source> { every { id } returns LocalSource.ID }

    @Test
    fun `metadata title does not change the local deletion target`() {
        val manga = Manga.create().copy(url = "content://storage/renamed-folder", ogTitle = "Different metadata title")
        val entry = mockk<UniFile>()
        every { fileSystem.getMangaEntry(manga.url) } returns entry

        assertSame(entry, provider.findMangaEntry(manga, localSource))
        verify(exactly = 1) { fileSystem.getMangaEntry(manga.url) }
    }

    @Test
    fun `standalone files target the file rather than its parent`() {
        val manga = Manga.create().copy(url = "file:///books/book.epub")
        val entry = mockk<UniFile>()
        every { fileSystem.getMangaEntry(manga.url) } returns entry

        assertSame(entry, provider.findMangaEntry(manga, localSource))
        verify(exactly = 0) { entry.parentFile }
    }

    @Test
    fun `missing local entry does not fall back to a title based folder`() {
        val manga = Manga.create().copy(url = "file:///missing", ogTitle = "Another folder")
        every { fileSystem.getMangaEntry(manga.url) } returns null

        assertNull(provider.findMangaEntry(manga, localSource))
    }

    @Test
    fun `online source retains its existing directory lookup`() {
        val source = mockk<Source> { every { id } returns 999L }
        val manga = Manga.create().copy(ogTitle = "Online title")
        val entry = mockk<UniFile>()
        val spy = io.mockk.spyk(provider)
        every { spy.findMangaDir(manga.ogTitle, source) } returns entry

        assertSame(entry, spy.findMangaEntry(manga, source))
        verify(exactly = 0) { fileSystem.getMangaEntry(any()) }
    }
}
