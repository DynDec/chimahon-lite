package tachiyomi.source.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.ByteArrayInputStream

class LocalMetadataSearchTest {
    private val context = mockk<Context>()
    private val fileSystem = mockk<LocalSourceFileSystem>()
    private val covers = mockk<LocalCoverManager>()
    private val folder = mockk<UniFile>()
    private val metadata = mockk<UniFile>()
    private val chapter = mockk<UniFile>()
    private val folderUrl = "content://storage/folder"
    private lateinit var source: LocalSource

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any()) } returns "Local"
        Injekt.addSingletonFactory<Json> { Json }
        source = LocalSource(context, fileSystem, covers) { false }
        every { fileSystem.usesDefaultBaseDirectory() } returns false
        every { fileSystem.getFilesInBaseDirectory() } returns listOf(folder)
        every { folder.isDirectory } returns true
        every { folder.name } returns "Filename title"
        val uri = mockk<Uri>()
        every { uri.toString() } returns folderUrl
        every { folder.uri } returns uri
        every { folder.listFiles() } returns arrayOf(metadata, chapter)
        every { fileSystem.getFilesInMangaDirectory(folderUrl) } returns listOf(metadata, chapter)
        every { metadata.name } returns "details.json"
        every { metadata.isDirectory } returns false
        every { chapter.name } returns "01"
        every { chapter.isDirectory } returns true
        every { covers.find(folderUrl) } returns null
        every { metadata.openInputStream() } answers {
            """{"title":"Metadata title","author":"藤本タツキ","artist":"Artist name"}""".byteInputStream()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `author artist and metadata title searches preserve URI without migrating files`() = runTest {
        for (query in listOf("藤本", "artist NAME", "metadata TITLE")) {
            val results = source.getSearchManga(1, query, FilterList()).mangas
            assertEquals(listOf(folderUrl), results.map { it.url })
        }
        verify(exactly = 0) { folder.createFile(any()) }
        verify(exactly = 0) { metadata.delete() }
        verify(exactly = 0) { metadata.openOutputStream() }
    }

    @Test
    fun `browsing and filename matches avoid reading metadata`() = runTest {
        assertEquals(1, source.getSearchManga(1, "", FilterList()).mangas.size)
        assertEquals(1, source.getSearchManga(1, "filename", FilterList()).mangas.size)
        verify(exactly = 0) { metadata.openInputStream() }
    }

    @Test
    fun `metadata streams close even when parsing fails`() = runTest {
        var closed = false
        every { metadata.openInputStream() } answers {
            object : ByteArrayInputStream("bad json".toByteArray()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        assertEquals(0, source.getSearchManga(1, "nonmatching", FilterList()).mangas.size)
        assertTrue(closed)
        verify(exactly = 0) { folder.createFile(any()) }
    }
}
