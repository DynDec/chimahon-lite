package eu.kanade.domain.manga.interactor

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.io.LocalSourceFileSystem

class RelinkFileResolutionTest {
    private val storage = mockk<StorageManager>()
    private val directory = mockk<UniFile>()
    private val chapter = mockk<UniFile>()

    @Test
    fun `missing absolute chapter location cannot open different content at the new location`() {
        every { storage.getFileFromUri("file:///old/01.cbz") } returns null
        val fs = LocalSourceFileSystem(storage)
        assertNull(fs.getChapterFile("file:///new", "file:///old/01.cbz"))
        verify(exactly = 0) { storage.getFileFromUri("file:///new") }
    }

    @Test
    fun `relative chapter paths from older backups still resolve`() {
        every { storage.getFileFromUri("OldBook/01.cbz") } returns null
        every { storage.getFileFromUri("file:///new") } returns directory
        every { directory.exists() } returns true
        every { directory.isDirectory } returns true
        every { directory.findFile("01.cbz") } returns chapter
        val fs = LocalSourceFileSystem(storage)
        assertSame(chapter, fs.getChapterFile("file:///new", "OldBook/01.cbz"))
    }
}
