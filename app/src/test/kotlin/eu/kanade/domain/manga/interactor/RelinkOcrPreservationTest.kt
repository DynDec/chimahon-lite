package eu.kanade.domain.manga.interactor

import android.content.Context
import chimahon.ocr.OcrCacheManager
import chimahon.ocr.OcrChapterData
import chimahon.ocr.OcrPageData
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File

class RelinkOcrPreservationTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `sidecar snapshot preserves internal pages and crop variants without writing to user files`() = runTest {
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        val fs = mockk<LocalSourceFileSystem>()
        Injekt.addSingletonFactory<LocalSourceFileSystem> { fs }
        val manga = Manga.create().copy(id = 5, source = LocalSource.ID, url = "file:///old")
        val chapter = Chapter.create().copy(id = 17, mangaId = 5, url = "file:///old/01")
        val source = mockk<LocalSource> { every { id } returns LocalSource.ID }
        val chapterDirectory = mockk<UniFile>()
        val sidecar = mockk<UniFile>()
        every { fs.getChapterFile(manga.url, chapter.url) } returns chapterDirectory
        every { chapterDirectory.isDirectory } returns true
        every { chapterDirectory.findFile(".ocr_cache.json") } returns sidecar
        val page = OcrPageData(emptyList(), "ja")
        val internal = OcrChapterData(mapOf(0 to page), mapOf("crop" to mapOf(0 to page)))
        val external = OcrChapterData(mapOf(1 to page), mapOf("crop" to mapOf(1 to page)))
        val target = File(directory, "ocr_cache/0/5/17.json")
        target.parentFile!!.mkdirs()
        target.writeText(Json.encodeToString(internal))
        every { sidecar.openInputStream() } answers { Json.encodeToString(external).byteInputStream() }
        val cache = OcrCacheManager(context, Json, mockk(), mockk())
        cache.preserveForRelink(manga, chapter, source)
        val preserved = Json.decodeFromString<OcrChapterData>(target.readText())
        assertEquals(setOf(0, 1), preserved.pages.keys)
        assertEquals(setOf(0, 1), preserved.variants["crop"]!!.keys)
        verify(exactly = 0) { sidecar.openOutputStream() }
    }
}
