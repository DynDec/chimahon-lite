package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.matchesLocalMangaSearch
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    // SY -->
    private val allowHiddenFiles: () -> Boolean,
    // SY <--
) : Source, UnmeteredSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LatestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        // SY -->
        val allowLocalSourceHiddenFolders = allowHiddenFiles()
        // SY <--
        val hideAppManagedDirectories = fileSystem.usesDefaultBaseDirectory()

        // Each manga folder is listed once and reused for the chapter check, cover and search metadata.
        var mangaEntries = fileSystem.getFilesInBaseDirectory()
            .filter { entry ->
                isVisible(entry, allowLocalSourceHiddenFolders) &&
                    (!hideAppManagedDirectories || !isReservedDefaultDirectory(entry))
            }
            .map { entry -> ListedEntry(entry, if (entry.isDirectory) entry.listFiles().orEmpty().toList() else null) }
            .filter { (entry, children) ->
                children?.any { isVisible(it, allowLocalSourceHiddenFolders) && isSupportedChapter(it) }
                    ?: isSupportedChapter(entry)
            }
            .filter { (entry) -> lastModifiedLimit == 0L || entry.lastModified() >= lastModifiedLimit }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    mangaEntries = if (filter.state!!.ascending) {
                        mangaEntries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.file.localTitle() })
                    } else {
                        mangaEntries.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.file.localTitle() })
                    }
                }
                is OrderBy.Latest -> {
                    mangaEntries = if (filter.state!!.ascending) {
                        mangaEntries.sortedBy { it.file.lastModified() }
                    } else {
                        mangaEntries.sortedByDescending { it.file.lastModified() }
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val metadataReads = Semaphore(4)
        val mangas = mangaEntries.map { (mangaEntry, children) ->
            async {
                val filenameTitle = mangaEntry.localTitle()
                val manga = SManga.create().apply {
                    title = filenameTitle
                    // Preserve the URI identity across folder changes and metadata title edits.
                    url = mangaEntry.uri.toString()
                    children?.let(coverManager::findIn)?.let { thumbnail_url = it.uri.toString() }
                }
                if (query.isNotBlank() && lastModifiedLimit == 0L) {
                    if (!filenameTitle.contains(query.trim(), ignoreCase = true)) {
                        metadataReads.withPermit { readSearchMetadata(mangaEntry, children, manga) }
                    }
                    manga.takeIf {
                        matchesLocalMangaSearch(query, filenameTitle, it.title, it.author, it.artist, it.genre)
                    }
                } else {
                    manga
                }
            }
        }.awaitAll().filterNotNull()

        MangasPage(mangas, false)
    }

    // SY -->
    fun updateMangaInfo(manga: SManga) {
        synchronized(searchMetadataCache) { searchMetadataCache.keys.removeAll { it.startsWith("${manga.url}\n") } }
        val mangaDirFiles = fileSystem.getFilesInMangaDirectory(manga.url)
        val existingFile = mangaDirFiles
            .firstOrNull { it.name == COMIC_INFO_FILE }
        val comicInfoArchiveFile = mangaDirFiles.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
        val comicInfoArchiveReader = comicInfoArchiveFile?.archiveReader(context)
        val existingComicInfo =
            (existingFile?.openInputStream() ?: comicInfoArchiveReader?.getInputStream(COMIC_INFO_FILE))?.use {
                AndroidXmlReader(it, StandardCharsets.UTF_8.name()).use { xmlReader ->
                    xml.decodeFromReader<ComicInfo>(xmlReader)
                }
            }
        val newComicInfo = if (existingComicInfo != null) {
            manga.run {
                existingComicInfo.copy(
                    series = ComicInfo.Series(title),
                    summary = description?.let { ComicInfo.Summary(it) },
                    writer = author?.let { ComicInfo.Writer(it) },
                    penciller = artist?.let { ComicInfo.Penciller(it) },
                    genre = genre?.let { ComicInfo.Genre(it) },
                    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                        ComicInfoPublishingStatus.toComicInfoValue(status.toLong()),
                    ),
                )
            }
        } else {
            manga.getComicInfo()
        }

        fileSystem.getMangaDirectory(manga.url)?.let {
            copyComicInfoFile(
                xml.encodeToString(ComicInfo.serializer(), newComicInfo).byteInputStream(),
                it,
                comicInfoArchiveReader?.encrypted ?: false,
            )
        }
    }
    // SY <--

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    private data class ListedEntry(val file: UniFile, val children: List<UniFile>?)

    private data class SearchMetadata(val title: String, val author: String?, val artist: String?, val genre: String?)

    /** Search metadata by entry URI and modification time, so repeated searches do not reopen every book. */
    private val searchMetadataCache = object : LinkedHashMap<String, SearchMetadata>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchMetadata>) = size > 1024
    }

    private enum class TopLevelMetadata { ComicInfo, ComicInfoArchive, LegacyJson }

    /** Reads a manga folder's own metadata file, if any, without changing anything on disk. */
    private fun readTopLevelMetadata(files: List<UniFile>, manga: SManga): TopLevelMetadata? {
        files.firstOrNull { it.name == COMIC_INFO_FILE }?.let { file ->
            file.openInputStream().use { setMangaDetailsFromComicInfoFile(it, manga) }
            return TopLevelMetadata.ComicInfo
        }
        // SY -->
        files.firstOrNull { it.name == COMIC_INFO_ARCHIVE }?.let { file ->
            getComicInfoForChapter(file) { stream, _ -> setMangaDetailsFromComicInfoFile(stream, manga) }
            return TopLevelMetadata.ComicInfoArchive
        }
        // SY <--
        // Old custom JSON format
        files.firstOrNull { it.extension == "json" }?.let { file ->
            file.openInputStream().use { stream ->
                json.decodeFromStream<MangaDetails>(stream).run {
                    title?.let { manga.title = it }
                    author?.let { manga.author = it }
                    artist?.let { manga.artist = it }
                    description?.let { manga.description = it }
                    genre?.let { manga.genre = it.joinToString() }
                    status?.let { manga.status = it }
                }
            }
            return TopLevelMetadata.LegacyJson
        }
        return null
    }

    /** Search must not migrate metadata, create .noxml files, or extract covers into the source folder. */
    private fun readSearchMetadata(entry: UniFile, children: List<UniFile>?, manga: SManga) {
        val key = "${entry.uri}\n${entry.lastModified()}"
        synchronized(searchMetadataCache) { searchMetadataCache[key] }?.let { cached ->
            manga.title = cached.title
            manga.author = cached.author
            manga.artist = cached.artist
            manga.genre = cached.genre
            return
        }
        try {
            val files = children ?: listOf(entry)
            when (readTopLevelMetadata(files, manga)) {
                null -> {
                    for (file in files.filter { isVisible(it, allowHiddenFiles()) && isSupportedChapter(it) }) {
                        if (file.extension.equals("epub", true)) {
                            file.epubReader(context).use { it.fillMetadata(manga, SChapter.create()) }
                            if (!manga.author.isNullOrBlank()) break
                        } else if (getComicInfoForChapter(file) { stream, _ ->
                                setMangaDetailsFromComicInfoFile(stream, manga)
                                true
                            } == true
                        ) {
                            break
                        }
                    }
                }
                else -> Unit
            }
            val metadata = SearchMetadata(manga.title, manga.author, manga.artist, manga.genre)
            synchronized(searchMetadataCache) { searchMetadataCache[key] = metadata }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error reading local search metadata for ${manga.title}" }
        }
    }

    // Manga details related
    private suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        val mangaDir = fileSystem.getMangaDirectory(manga.url)
        val mangaDirFiles = mangaDir?.listFiles().orEmpty().toList()
        coverManager.findIn(mangaDirFiles)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            mangaDir ?: return@withIOContext manga

            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }

            when (readTopLevelMetadata(mangaDirFiles, manga)) {
                // Top level ComicInfo.xml, or SY's packed ComicInfo
                TopLevelMetadata.ComicInfo, TopLevelMetadata.ComicInfoArchive -> {
                    noXmlFile?.delete()
                }

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                TopLevelMetadata.LegacyJson -> {
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile!!.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                null -> if (noXmlFile == null) {
                    val chapterArchives = mangaDirFiles.filter(Archive::isSupported)

                    // SY -->
                    // The copy is a plain ComicInfo.xml or, for encrypted chapters, a packed one.
                    val copiedFile = copyComicInfoFileFromChapters(chapterArchives, mangaDir)
                    if (copiedFile == null || readTopLevelMetadata(listOf(copiedFile), manga) == null) {
                        // SY <--
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream, ArchiveReader?) -> T): T? {
        if (chapter.isDirectory) {
            return chapter.findFile(COMIC_INFO_FILE)?.let { file ->
                file.openInputStream().use { block(it, /* SY --> */ null /* SY <-- */) }
            }
        } else {
            return chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use { block(it, /* SY --> */ reader /* SY <-- */) }
            }
        }
    }

    private fun copyComicInfoFileFromChapters(chapterArchives: List<UniFile>, folder: UniFile): UniFile? {
        for (chapter in chapterArchives) {
            val file = getComicInfoForChapter(chapter) f@{ stream, /* SY --> */ reader /* SY <-- */ ->
                return@f copyComicInfoFile(stream, folder, /* SY --> */ reader?.encrypted == true /* SY <-- */)
            }
            if (file != null) return file
        }
        return null
    }

    private fun copyComicInfoFile(
        comicInfoFileStream: InputStream,
        folder: UniFile,
        // SY -->
        encrypt: Boolean,
        // SY <--
    ): UniFile? {
        // SY -->
        if (encrypt) {
            val comicInfoArchiveFile = folder.createFile(COMIC_INFO_ARCHIVE)
            comicInfoArchiveFile?.let { archive ->
                ZipWriter(context, archive, encrypt = true).use { writer ->
                    writer.write(comicInfoFileStream.use { it.readBytes() }, COMIC_INFO_FILE)
                }
            }
            return comicInfoArchiveFile
        } else {
            // SY <--
            return folder.createFile(COMIC_INFO_FILE)?.apply {
                openOutputStream().use { outputStream ->
                    comicInfoFileStream.use { it.copyTo(outputStream) }
                }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }
    }

    // Chapters
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        // One listing serves both the chapters and the folder cover.
        val mangaDirectory = fileSystem.getMangaDirectory(manga.url)
        val mangaFiles = mangaDirectory?.listFiles()?.toList() ?: fileSystem.getFilesInMangaDirectory(manga.url)
        val chapters = mangaFiles
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }
            .map { chapterFile ->
                SChapter.create().apply {
                    url = chapterFile.uri.toString()
                    name = if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }.orEmpty()
                    date_upload = chapterFile.lastModified()
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()

                    val format = Format.valueOf(chapterFile)
                    if (format is Format.Epub) {
                        format.file.epubReader(context).use { epub ->
                            epub.fillMetadata(manga, this)
                        }
                    } else {
                        getComicInfoForChapter(chapterFile) { stream, /* SY --> */ _ /* SY <-- */ ->
                            setChapterDetailsFromComicInfoFile(stream, this)
                        }
                    }
                }
            }
            .sortedWith { c1, c2 ->
                c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
            }

        // A folder cover wins; otherwise point at the first chapter's first image without extracting it.
        manga.thumbnail_url = mangaDirectory?.let { coverManager.findIn(mangaFiles) }?.uri?.toString()
            ?: chapters.lastOrNull()?.let { fileSystem.getChapterFile(it.url) }?.let { chapter ->
                manga.thumbnail_url.takeIf { coverManager.belongsTo(it, chapter) } ?: chapterCover(chapter, manga.title)
            }
            ?: manga.thumbnail_url

        chapters
    }

    /** Cover for [mangaUrl] from its folder or first chapter, without parsing chapter metadata. */
    fun resolveCover(mangaUrl: String): String? {
        val mangaDirectory = fileSystem.getMangaDirectory(mangaUrl)
        val mangaFiles = mangaDirectory?.listFiles()?.toList() ?: fileSystem.getFilesInMangaDirectory(mangaUrl)
        mangaDirectory?.let { coverManager.findIn(mangaFiles) }?.let { return it.uri.toString() }
        val allowHidden = allowHiddenFiles()
        return mangaFiles
            .filter { isVisible(it, allowHidden) && isSupportedChapter(it) }
            .minWithOrNull { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
            ?.let { chapterCover(it, mangaUrl) }
    }

    private fun chapterCover(chapter: UniFile, label: String): String? = try {
        coverManager.forChapter(chapter)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e) { "Error finding cover for $label" }
        null
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    fun getFormat(chapter: SChapter): Format {
        try {
            return fileSystem.getChapterFile(chapter.url)
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (_: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://komikku-app.github.io/docs/guides/local-source/"

        // SY -->
        const val COMIC_INFO_ARCHIVE = "ComicInfo.cbm"
        // SY <--

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

private val RESERVED_DEFAULT_DIRECTORIES = setOf(
    "autobackup",
    "downloads",
    "local",
    "localanime",
    "logs",
    "mpv",
    "scripts",
    "script-opts",
)

private fun isVisible(file: UniFile, allowHiddenFiles: Boolean): Boolean {
    return allowHiddenFiles || !file.name.orEmpty().startsWith('.')
}

private fun isSupportedChapter(file: UniFile): Boolean {
    return file.isDirectory || Archive.isSupported(file) || file.extension.equals("epub", ignoreCase = true)
}

private fun isReservedDefaultDirectory(file: UniFile): Boolean {
    return file.isDirectory && file.name.orEmpty().lowercase() in RESERVED_DEFAULT_DIRECTORIES
}

private fun UniFile.localTitle(): String {
    return if (isDirectory) name.orEmpty() else nameWithoutExtension.orEmpty()
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
