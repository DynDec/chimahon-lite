package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.InputStream

/**
 * One entry of a volume's table of contents.
 *
 * @property pageIndex zero-based index into the volume's page list as the reader orders it.
 * @property level nesting depth, 0 for top-level entries.
 */
data class TocEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int,
)

/**
 * A table of contents link whose [href] still has to be mapped to a page.
 */
internal data class TocLink(
    val title: String,
    val href: String,
    val level: Int,
)

/**
 * A spine document and the images it shows, in reading order.
 */
internal data class SpineDocument(
    val path: String,
    val images: List<String>,
)

private const val COMIC_INFO_FILE = "ComicInfo.xml"
private val WHITESPACE = Regex("\\s+")

/**
 * Returns the bookmarked pages listed in the archive's ComicInfo.xml, or an empty list when the
 * archive has no ComicInfo.xml or it has no usable bookmarks.
 */
fun ArchiveReader.getComicInfoToc(pageCount: Int): List<TocEntry> {
    // Prefer the root-level file when an archive carries more than one.
    val entryName = useEntries { entries ->
        entries
            .filter { it.isFile && it.name.substringAfterLast('/').equals(COMIC_INFO_FILE, ignoreCase = true) }
            .minByOrNull { entry -> entry.name.count { it == '/' } }
            ?.name
    } ?: return emptyList()
    return getInputStream(entryName)?.use { parseComicInfoToc(it, pageCount) }.orEmpty()
}

/**
 * Reads `<Pages><Page Image="n" Bookmark="title"/></Pages>` from a ComicInfo document. `Image` is the
 * zero-based page index; pages without a bookmark or outside [pageCount] are skipped.
 */
fun parseComicInfoToc(stream: InputStream, pageCount: Int): List<TocEntry> {
    val document = Jsoup.parse(stream, null, "", Parser.xmlParser())
    return document.elementsByLocalName("Page")
        .filter { it.parent()?.hasLocalName("Pages") == true }
        .mapNotNull { page ->
            val title = page.attrIgnoreCase("Bookmark").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val pageIndex = page.attrIgnoreCase("Image").trim().toIntOrNull()
                ?.takeIf { it in 0..<pageCount }
                ?: return@mapNotNull null
            TocEntry(title, pageIndex, level = 0)
        }
        .sortedBy { it.pageIndex }
}

/**
 * Reads the `toc` navigation of an EPUB 3 navigation document. A nav without any `epub:type` is
 * accepted when no nav is typed as `toc`.
 */
internal fun parseNavToc(document: Document): List<TocLink> {
    val navs = document.elementsByLocalName("nav")
    val tocNav = navs.firstOrNull { nav -> "toc" in nav.attrIgnoreCase("epub:type").split(WHITESPACE) }
        ?: navs.firstOrNull { nav -> nav.attrIgnoreCase("epub:type").isBlank() }
        ?: return emptyList()
    val list = tocNav.children().firstOrNull { it.hasLocalName("ol") } ?: return emptyList()

    val result = mutableListOf<TocLink>()
    collectNavList(list, level = 0, result)
    return result
}

private fun collectNavList(list: Element, level: Int, result: MutableList<TocLink>) {
    list.children()
        .filter { it.hasLocalName("li") }
        .forEach { item ->
            val label = item.children().firstOrNull { it.hasLocalName("a") || it.hasLocalName("span") }
            val title = label?.text()?.trim()?.ifEmpty { label.attrIgnoreCase("title").trim() }.orEmpty()
            val href = label?.takeIf { it.hasLocalName("a") }?.attrIgnoreCase("href")?.trim().orEmpty()
            // Heading-only entries (span) have no target of their own; their children still count.
            if (title.isNotEmpty() && href.isNotEmpty()) {
                result += TocLink(title, href, level)
            }
            item.children()
                .filter { it.hasLocalName("ol") }
                .forEach { collectNavList(it, level + 1, result) }
        }
}

/**
 * Reads the `navMap` of an EPUB 2 NCX document.
 */
internal fun parseNcxToc(document: Document): List<TocLink> {
    val navMap = document.elementsByLocalName("navMap").firstOrNull() ?: return emptyList()
    val result = mutableListOf<TocLink>()
    collectNavPoints(navMap, level = 0, result)
    return result
}

private fun collectNavPoints(parent: Element, level: Int, result: MutableList<TocLink>) {
    parent.children()
        .filter { it.hasLocalName("navPoint") }
        .forEach { point ->
            val title = point.children()
                .firstOrNull { it.hasLocalName("navLabel") }
                ?.elementsByLocalName("text")
                ?.firstOrNull()
                ?.text()
                ?.trim()
                .orEmpty()
            val src = point.children()
                .firstOrNull { it.hasLocalName("content") }
                ?.attrIgnoreCase("src")
                ?.trim()
                .orEmpty()
            if (title.isNotEmpty() && src.isNotEmpty()) {
                result += TocLink(title, src, level)
            }
            collectNavPoints(point, level + 1, result)
        }
}

/**
 * Maps links whose [TocLink.href] is already a resolved archive path to page indices. A link to a
 * spine document lands on its first image, or on the next image when the document shows none; a
 * link straight to an image lands on that image. Links that match nothing are dropped.
 */
internal fun mapTocLinks(links: List<TocLink>, spine: List<SpineDocument>): List<TocEntry> {
    val documentStarts = mutableMapOf<String, Int>()
    val imageIndices = mutableMapOf<String, Int>()
    var offset = 0
    spine.forEach { document ->
        documentStarts.putIfAbsent(document.path, offset)
        document.images.forEachIndexed { i, image -> imageIndices.putIfAbsent(image, offset + i) }
        offset += document.images.size
    }

    return links.mapNotNull { link ->
        val pageIndex = (documentStarts[link.href] ?: imageIndices[link.href])
            ?.takeIf { it < offset }
            ?: return@mapNotNull null
        TocEntry(link.title, pageIndex, link.level)
    }
}

internal fun Element.elementsByLocalName(name: String): List<Element> {
    return allElements.filter { it.hasLocalName(name) }
}

internal fun Element.hasLocalName(name: String): Boolean {
    return tagName().substringAfter(':').equals(name, ignoreCase = true)
}

private fun Element.attrIgnoreCase(key: String): String {
    return attributes().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()
}
