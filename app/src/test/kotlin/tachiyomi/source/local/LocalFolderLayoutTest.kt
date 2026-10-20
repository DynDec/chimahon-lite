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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalFolderLayout
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory

class LocalFolderLayoutTest {
    private val context = mockk<Context>()
    private val fileSystem = mockk<LocalSourceFileSystem>()
    private val covers = mockk<LocalCoverManager>()
    private val contents = HashMap<UniFile, List<UniFile>>()
    private lateinit var source: LocalSource

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any()) } returns "Local"
        Injekt.addSingletonFactory<Json> { Json }
        source = LocalSource(context, fileSystem, covers) { false }
        every { fileSystem.usesDefaultBaseDirectory() } returns false
        every { covers.findIn(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `publisher folders list each series with folder, archive or epub chapters`() = runTest {
        base(
            dir(
                "Publisher",
                dir("Folders", dir("Ch 1", file("01.jpg"))),
                dir("Archives", file("Ch 1.cbz")),
                dir("Books", file("Vol 1.epub")),
            ),
        )

        assertEquals(
            listOf("Publisher/Archives", "Publisher/Books", "Publisher/Folders"),
            browse(),
        )
    }

    @Test
    fun `loose books in a publisher folder are listed as their own manga`() = runTest {
        base(dir("Publisher", dir("Series", file("Ch 1.cbz")), file("Oneshot.cbz")))

        assertEquals(listOf("Publisher/Oneshot.cbz", "Publisher/Series"), browse())
    }

    @Test
    fun `series folders stay one manga`() = runTest {
        base(
            dir("Folders", dir("Ch 1", file("01.jpg")), dir("Ch 2", file("01.jpg"))),
            dir("Archives", file("Ch 1.cbz")),
        )

        assertEquals(listOf("Archives", "Folders"), browse())
    }

    @Test
    fun `images or metadata in the folder itself keep it one series`() = runTest {
        for (own in listOf("logo.PNG", "cover.jpeg", "ComicInfo.xml", "ComicInfo.cbm", "details.json")) {
            base(dir("Publisher", file(own), dir("Series", file("Ch 1.cbz"))))

            assertEquals(listOf("Publisher"), browse(), own)
        }
    }

    @Test
    fun `markers override detection`() = runTest {
        base(dir("Publisher", file(LocalFolderLayout.SERIES_MARKER), dir("Series", file("Ch 1.cbz"))))
        assertEquals(listOf("Publisher"), browse())

        base(dir("Publisher", file(LocalFolderLayout.COLLECTION_MARKER), file("logo.png"), dir("Series", dir("Ch"))))
        assertEquals(listOf("Publisher/Series"), browse())
    }

    @Test
    fun `a series lists only its first chapter to decide, and only once while unchanged`() = runTest {
        val first = dir("Ch 1", file("01.jpg"))
        val second = dir("Ch 2", file("01.jpg"))
        base(dir("Series", second, first))

        repeat(3) { assertEquals(listOf("Series"), browse()) }

        verify(exactly = 1) { first.listFiles() }
        verify(exactly = 0) { second.listFiles() }
    }

    @Test
    fun `the folder layout is found from a series and changed with marker files`() {
        val marker = file(LocalFolderLayout.COLLECTION_MARKER)
        val publisher = dir("Publisher", marker, dir("Series", file("Ch 1.cbz")))
        base(publisher)

        val layout = source.getFolderLayout("$ROOT/Publisher%2FSeries")!!
        assertEquals("Publisher", layout.folder.name)
        assertTrue(layout.isCollection)
        assertTrue(layout.canSplit)

        every { publisher.findFile(LocalFolderLayout.COLLECTION_MARKER) } returns marker
        every { publisher.findFile(LocalFolderLayout.SERIES_MARKER) } returns null
        every { marker.delete() } returns true
        every { publisher.createFile(LocalFolderLayout.SERIES_MARKER) } returns mockk()
        assertTrue(source.setFolderLayout(publisher, isCollection = false))
        verify { marker.delete() }
        verify { publisher.createFile(LocalFolderLayout.SERIES_MARKER) }
    }

    @Test
    fun `a series of page folders cannot be split and a collection marker does not hide it`() {
        base(dir("Series", dir("Vol 1", file("001.jpg")), dir("Vol 2", file("001.jpg"))))
        val layout = source.getFolderLayout("$ROOT/Series")!!
        assertFalse(layout.isCollection)
        assertFalse(layout.canSplit)

        base(dir("Series", file(LocalFolderLayout.COLLECTION_MARKER), dir("Vol 1", file("001.jpg"))))
        runTest { assertEquals(listOf("Series"), browse()) }
    }

    @Test
    fun `child URIs match only direct children`() {
        assertTrue(LocalFolderLayout.isChildUri("content://t/document/a%2FP%2FS", "content://t/document/a%2FP"))
        assertTrue(LocalFolderLayout.isChildUri("file:///m/P/S", "file:///m/P/"))
        assertFalse(LocalFolderLayout.isChildUri("content://t/document/a%2FP%2FS%2FC", "content://t/document/a%2FP"))
        assertFalse(LocalFolderLayout.isChildUri("content://t/document/a%2FPublisher2", "content://t/document/a%2FP"))
        assertFalse(LocalFolderLayout.isChildUri("content://t/document/a%2FP", "content://t/document/a%2FP"))
    }

    private suspend fun browse(): List<String> {
        return source.getSearchManga(1, "", FilterList()).mangas
            .map { it.url.removePrefix("$ROOT/").replace("%2F", "/") }
            .sorted()
    }

    private fun base(vararg entries: UniFile) {
        entries.forEach { place(it, ROOT) }
        every { fileSystem.getFilesInBaseDirectory() } returns entries.toList()
    }

    /** Gives [entry] and its descendants URIs below [parent], like tree documents. */
    private fun place(entry: UniFile, parent: String) {
        val path = if (parent == ROOT) "$ROOT/${entry.name}" else "$parent%2F${entry.name}"
        val uri = mockk<Uri>()
        every { uri.toString() } returns path
        every { entry.uri } returns uri
        contents[entry]?.forEach { place(it, path) }
    }

    private fun dir(name: String, vararg children: UniFile): UniFile = mockk<UniFile>().also {
        contents[it] = children.toList()
        every { it.name } returns name
        every { it.isDirectory } returns true
        every { it.isFile } returns false
        every { it.listFiles() } returns arrayOf(*children)
        every { it.lastModified() } returns 1L
    }

    private fun file(name: String): UniFile = mockk<UniFile>().also {
        every { it.name } returns name
        every { it.isDirectory } returns false
        every { it.isFile } returns true
        every { it.lastModified() } returns 1L
    }

    private companion object {
        const val ROOT = "content://tree/document/primary%3AManga"
    }
}
