package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.TocEntry
import mihon.core.archive.parseComicInfoToc
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(val file: UniFile) : PageLoader() {

    override var isLocal: Boolean = true

    // Chimahon -->
    private var pageCount = 0
    private val contents by lazy {
        file.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals(COMIC_INFO_FILE, ignoreCase = true) }
            ?.openInputStream()
            ?.use { parseComicInfoToc(it, pageCount) }
            .orEmpty()
    }

    override suspend fun getPages(): List<ReaderPage> = loadPages().also { pageCount = it.size }

    override fun getTableOfContents(): List<TocEntry> = contents

    private fun loadPages(): List<ReaderPage> {
        // Chimahon <--
        return file.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            ?.mapIndexed { i, file ->
                val streamFn = { file.openInputStream() }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
            .orEmpty()
    }
}
