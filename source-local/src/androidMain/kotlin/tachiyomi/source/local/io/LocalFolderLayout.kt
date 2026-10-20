package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.source.local.LocalSource.Companion.COMIC_INFO_ARCHIVE

/**
 * Decides whether a top-level folder is a collection, such as a publisher, whose subfolders are each a series.
 *
 * A folder is a collection when it holds no images or metadata of its own and one of its subfolders holds
 * chapters (folders, archives or epubs). A subfolder of pages makes it a series instead. A [SERIES_MARKER] or
 * [COLLECTION_MARKER] file in the folder overrides the detection.
 */
object LocalFolderLayout {

    const val SERIES_MARKER = ".series"
    const val COLLECTION_MARKER = ".collection"

    private val IMAGE_EXTENSIONS = ImageUtil.ImageType.entries.map { it.extension }.toSet() + setOf("jpeg", "cbi")

    /**
     * [children] is the folder's own listing. [listFiles] lists one of its subfolders and is called only
     * until a subfolder decides the layout.
     */
    fun isCollection(
        children: List<UniFile>,
        allowHiddenFiles: Boolean,
        listFiles: (UniFile) -> List<UniFile>,
    ): Boolean {
        return decideFromOwnFiles(children, allowHiddenFiles)
            ?: decideFromSubfolders(children, allowHiddenFiles, listFiles)
    }

    /** The layout when the folder's own [children] decide it, or null when a subfolder has to be listed. */
    fun decideFromOwnFiles(children: List<UniFile>, allowHiddenFiles: Boolean): Boolean? {
        when (marker(children)) {
            SERIES_MARKER -> return false
            COLLECTION_MARKER -> return true
        }
        if (children.any { !it.isDirectory && (isImage(it) || isMetadata(it)) }) return false
        if (children.none { it.isDirectory && isVisible(it, allowHiddenFiles) }) return false
        return null
    }

    /** Lists subfolders in name order until one holds pages (a series) or chapters (a collection). */
    fun decideFromSubfolders(
        children: List<UniFile>,
        allowHiddenFiles: Boolean,
        listFiles: (UniFile) -> List<UniFile>,
    ): Boolean {
        val subfolders = children
            .filter { it.isDirectory && isVisible(it, allowHiddenFiles) }
            .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
        for (subfolder in subfolders) {
            val files = listFiles(subfolder).filter { isVisible(it, allowHiddenFiles) }
            if (files.any { !it.isDirectory && isImage(it) }) return false
            if (files.any { it.isDirectory || isChapterFile(it) }) return true
        }
        return false
    }

    /**
     * Whether listing the folder as a collection would list any series: a subfolder must hold chapters. Subfolders
     * of pages are chapters themselves, and a collection lists nothing for them.
     */
    fun hasSeries(children: List<UniFile>, allowHiddenFiles: Boolean, listFiles: (UniFile) -> List<UniFile>): Boolean {
        return children
            .filter { it.isDirectory && isVisible(it, allowHiddenFiles) }
            .any { subfolder ->
                listFiles(subfolder).any { isVisible(it, allowHiddenFiles) && (it.isDirectory || isChapterFile(it)) }
            }
    }

    /** The marker in [children] that overrides detection, if any. */
    fun marker(children: List<UniFile>): String? {
        val names = children.mapNotNullTo(HashSet()) { it.name }
        return when {
            SERIES_MARKER in names -> SERIES_MARKER
            COLLECTION_MARKER in names -> COLLECTION_MARKER
            else -> null
        }
    }

    /** Whether [file]'s URI names a direct child of [parent]'s URI, for both tree documents and plain files. */
    fun isChildUri(file: String, parent: String): Boolean {
        val rest = file.removePrefix(parent.trimEnd('/'))
        if (rest == file) return false
        val name = when {
            rest.startsWith("/") -> rest.substring(1)
            rest.startsWith("%2F", ignoreCase = true) -> rest.substring(3)
            else -> return false
        }
        return name.isNotEmpty() && "/" !in name.trimEnd('/') && !name.contains("%2F", ignoreCase = true)
    }

    private fun isVisible(file: UniFile, allowHiddenFiles: Boolean): Boolean {
        return allowHiddenFiles || !file.name.orEmpty().startsWith('.')
    }

    private fun isImage(file: UniFile): Boolean {
        return file.extension?.lowercase() in IMAGE_EXTENSIONS
    }

    private fun isMetadata(file: UniFile): Boolean {
        val name = file.name.orEmpty()
        return name.equals(COMIC_INFO_FILE, ignoreCase = true) ||
            name.equals(COMIC_INFO_ARCHIVE, ignoreCase = true) ||
            file.extension.equals("json", ignoreCase = true)
    }

    private fun isChapterFile(file: UniFile): Boolean {
        return Archive.isSupported(file) || file.extension.equals("epub", ignoreCase = true)
    }
}
