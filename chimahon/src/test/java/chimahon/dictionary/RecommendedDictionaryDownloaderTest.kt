package chimahon.dictionary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class RecommendedDictionaryDownloaderTest {
    @TempDir
    lateinit var cacheDir: File

    private val dictionary = RecommendedDictionary(
        "test",
        "Test",
        "ja",
        RecommendedDictionaryKind.Term,
        indexUrl = "https://example.com/index.json",
    )

    @Test
    fun `index download streams archive to importer and removes temporary file`() = runTest {
        val requests = mutableListOf<String>()
        val stages = mutableListOf<RecommendedDictionaryStage>()
        val bodies = mutableListOf<TrackingBody>()
        val downloader = downloader(requests) { url ->
            TrackingBody(
                if (url.endsWith("index.json")) {
                    """{"title":"Test","downloadUrl":"https://example.com/dictionary.zip"}"""
                } else {
                    "archive bytes"
                },
            ).also { bodies += it }
        }
        var importedFile: File? = null
        val result = downloader.downloadAndImport(dictionary, cacheDir, stages::add) { file ->
            importedFile = file
            assertEquals("archive bytes", file.readText())
            "imported"
        }
        assertEquals("imported", result)
        assertEquals(listOf(dictionary.indexUrl, "https://example.com/dictionary.zip"), requests)
        assertEquals(RecommendedDictionaryStage.entries, stages)
        assertTrue(bodies.all { it.closed })
        assertFalse(importedFile!!.exists())
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `direct archive skips index request`() = runTest {
        val requests = mutableListOf<String>()
        val direct = dictionary.copy(downloadUrl = "https://example.com/direct.zip")
        downloader(requests).downloadAndImport(direct, cacheDir) { assertTrue(it.isFile) }
        assertEquals(listOf(direct.downloadUrl), requests)
    }

    @Test
    fun `HTTP error prevents import and closes response`() {
        val body = TrackingBody("not found")
        val downloader = downloader(status = 404) { body }
        assertThrows(IOException::class.java) {
            runTest { downloader.downloadAndImport(dictionary.copy(downloadUrl = "https://example.com/file.zip"), cacheDir) { error("Must not import") } }
        }
        assertTrue(body.closed)
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `incomplete download is removed without import`() {
        assertThrows(IOException::class.java) {
            runTest {
                downloader { TrackingBody("partial", expectedLength = 100) }.downloadAndImport(
                    dictionary.copy(downloadUrl = "https://example.com/file.zip"),
                    cacheDir,
                ) { error("Must not import") }
            }
        }
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `import failure and cancellation remove temporary archive`() {
        for (failure in listOf(IOException("Import failed"), CancellationException("Cancelled"))) {
            assertThrows(failure.javaClass) {
                runTest {
                    downloader().downloadAndImport(
                        dictionary.copy(downloadUrl = "https://example.com/file.zip"),
                        cacheDir,
                    ) { throw failure }
                }
            }
            assertTrue(cacheDir.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `missing or insecure download URL prevents archive request`() {
        for (index in listOf("{}", """{"downloadUrl":"http://example.com/file.zip"}""", """{"downloadUrl":""}""")) {
            val requests = mutableListOf<String>()
            assertThrows(Exception::class.java) {
                runTest { downloader(requests) { TrackingBody(index) }.downloadAndImport(dictionary, cacheDir) { error("Must not import") } }
            }
            assertEquals(listOf(dictionary.indexUrl), requests)
            assertTrue(cacheDir.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `catalog includes Japanese Hoshi entries and Pixiv Light without English dictionaries`() {
        assertEquals(5, recommendedDictionariesForLanguage("").size)
        assertTrue(recommendedDictionariesForLanguage("en-US").isEmpty())
        val japanese = recommendedDictionariesForLanguage("JA_jp")
        assertEquals(setOf("jmdict", "jmnedict", "jiten", "jitendex", "pixiv-light"), japanese.map { it.id }.toSet())
        assertTrue(japanese.single { it.id == "pixiv-light" }.indexUrl.endsWith("/pixiv_light_index.json"))
        assertTrue(recommendedDictionariesForLanguage("ko").isEmpty())
    }

    private fun downloader(
        requests: MutableList<String> = mutableListOf(),
        status: Int = 200,
        body: (String) -> TrackingBody = { TrackingBody("archive") },
    ) = RecommendedDictionaryDownloader(
        OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test response").body(body(url)).build()
        }.build(),
    )

    private class TrackingBody(text: String, private val expectedLength: Long = text.toByteArray().size.toLong()) : ResponseBody() {
        var closed = false
        private val buffered = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = expectedLength
        override fun source(): BufferedSource = buffered
    }
}
