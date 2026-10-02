package chimahon.dictionary

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

enum class RecommendedDictionaryStage { Fetching, Downloading, Importing }

class RecommendedDictionaryDownloader(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DownloadIndex(val downloadUrl: String)

    suspend fun <T> downloadAndImport(
        dictionary: RecommendedDictionary,
        cacheDir: File,
        onProgress: (RecommendedDictionaryStage) -> Unit = {},
        importArchive: suspend (File) -> T,
    ): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val url = dictionary.downloadUrl.ifBlank {
            onProgress(RecommendedDictionaryStage.Fetching)
            client.newCall(Request.Builder().url(dictionary.indexUrl).build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("Dictionary index HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty dictionary index")
                json.decodeFromString<DownloadIndex>(body.string()).downloadUrl
            }
        }
        require(url.startsWith("https://")) { "Dictionary download requires an HTTPS URL" }
        currentCoroutineContext().ensureActive()
        val archive = File.createTempFile("recommended_dictionary_", ".zip", cacheDir)
        try {
            onProgress(RecommendedDictionaryStage.Downloading)
            client.newCall(Request.Builder().url(url).build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("Dictionary download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty dictionary download")
                body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val expectedLength = body.contentLength()
                if (archive.length() == 0L || (expectedLength >= 0 && archive.length() != expectedLength)) {
                    throw IOException("Incomplete dictionary download")
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(RecommendedDictionaryStage.Importing)
            importArchive(archive)
        } finally {
            archive.delete()
        }
    }
}
