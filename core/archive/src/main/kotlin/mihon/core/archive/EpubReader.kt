package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        // Chimahon -->
        return spine.flatMap { it.images }
        // Chimahon <--
    }

    // Chimahon -->
    /**
     * Returns the table of contents declared by the EPUB 3 navigation document, or by the EPUB 2
     * NCX when there is no usable navigation document. Page indices follow [getImagesFromPages].
     */
    fun getTableOfContents(): List<TocEntry> {
        val navEntries = getNavDocumentPath()
            ?.let { path -> resolveTocLinks(path, parseNavToc(parseXml(path) ?: return@let null)) }
            .orEmpty()
        if (navEntries.isNotEmpty()) return navEntries

        return getNcxPath()
            ?.let { path -> resolveTocLinks(path, parseNcxToc(parseXml(path) ?: return@let null)) }
            .orEmpty()
    }

    /**
     * Spine documents with their images, parsed once and shared by page and contents lookups.
     */
    private val spine: List<SpineDocument> by lazy {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val basePath = getParentDirectory(ref)
        getPagesFromDocument(doc).map { page ->
            val path = resolveZipPath(basePath, page)
            SpineDocument(path, getImagesFromPage(path))
        }
    }

    private fun getNavDocumentPath(): String? {
        val packageHref = getPackageHref()
        val href = getPackageDocument(packageHref).manifestItems()
            .firstOrNull { item -> "nav" in item.attr("properties").split(Regex("\\s+")) }
            ?.attr("href")
            ?: return null
        return resolveHrefPath(getParentDirectory(packageHref), href)
    }

    private fun getNcxPath(): String? {
        val packageHref = getPackageHref()
        val document = getPackageDocument(packageHref)
        val manifestItems = document.manifestItems()
        val tocId = document.elementsByLocalName("spine").firstOrNull()?.attr("toc").orEmpty()
        val item = manifestItems.firstOrNull { tocId.isNotEmpty() && it.attr("id") == tocId }
            ?: manifestItems.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
            ?: return null
        return resolveHrefPath(getParentDirectory(packageHref), item.attr("href"))
    }

    private fun resolveTocLinks(tocPath: String, links: List<TocLink>): List<TocEntry> {
        val basePath = getParentDirectory(tocPath)
        val resolved = links.mapNotNull { link ->
            resolveHrefPath(basePath, link.href)?.let { link.copy(href = it) }
        }
        return mapTocLinks(resolved, spine)
    }

    /**
     * Resolves a relative in-book href against [basePath]. Returns null for external links and
     * for fragment-only links, which do not name a document.
     */
    private fun resolveHrefPath(basePath: String, href: String): String? {
        if (href.contains("://") || href.startsWith("mailto:")) return null
        val path = decodeHrefPath(href).takeIf { it.isNotEmpty() } ?: return null
        return resolveZipPath(basePath, path)
    }

    private fun parseXml(path: String): Document? {
        return getInputStream(path)?.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    private fun Document.manifestItems(): List<Element> {
        return elementsByLocalName("item").filter { it.parent()?.hasLocalName("manifest") == true }
    }
    // Chimahon <--

    /**
     * Returns the cover image path declared by the package metadata, or the first image in the
     * first spine page when no usable cover metadata is present.
     */
    fun getCoverImage(): String? {
        val packageHref = getPackageHref()
        val packageDocument = getPackageDocument(packageHref)

        findMetadataCover(packageDocument, packageHref)?.let { return it }

        // Stop at the first page with an image instead of parsing the whole book.
        val basePath = getParentDirectory(packageHref)
        return getPagesFromDocument(packageDocument).asSequence()
            .flatMap { page -> getImagesFromPage(resolveZipPath(basePath, page)) }
            .firstOrNull()
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.elementsByLocalName("rootfile").firstOrNull()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Returns all the pages from the epub.
     */
    private fun getPagesFromDocument(document: Document): List<String> {
        val pages = document.elementsByLocalName("item")
            .filter { it.parent()?.hasLocalName("manifest") == true }
            .filter { node -> "application/xhtml+xml" == node.attr("media-type") }
            .associateBy { it.attr("id") }

        val spine = document.elementsByLocalName("itemref")
            .filter { it.parent()?.hasLocalName("spine") == true }
            .map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") }
    }

    /**
     * Returns all image paths referenced by one XHTML/SVG document.
     */
    private fun getImagesFromPage(entryPath: String): List<String> {
        val document = getInputStream(entryPath)!!.use { Jsoup.parse(it, null, "") }
        val imageBasePath = getParentDirectory(entryPath)

        return document.allElements.mapNotNull { element ->
            when {
                element.hasLocalName("img") -> element.attr("src")
                element.hasLocalName("image") -> element.attr("xlink:href").ifEmpty { element.attr("href") }
                else -> ""
            }
                .takeIf { it.isNotEmpty() }
                ?.let(::decodeHrefPath)
                ?.takeIf { it.isNotEmpty() }
                ?.let { resolveZipPath(imageBasePath, it) }
        }
    }

    /**
     * Selects EPUB3, EPUB2, and legacy guide cover declarations in that order.
     */
    private fun findMetadataCover(document: Document, packageHref: String): String? {
        val manifestItems = document.elementsByLocalName("item")
            .filter { it.parent()?.hasLocalName("manifest") == true }

        // EPUB 3: the manifest item itself is the cover image.
        manifestItems
            .firstOrNull { item ->
                item.attr("properties")
                    .split(Regex("\\s+"))
                    .any { it.equals("cover-image", ignoreCase = true) }
            }
            ?.let { item ->
                resolveMetadataItem(item, packageHref)?.let { return it }
            }

        // EPUB 2: metadata names the manifest item by id.
        document.elementsByLocalName("meta")
            .firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { coverId ->
                manifestItems.firstOrNull { it.attr("id") == coverId }
                    ?.let { item -> resolveMetadataItem(item, packageHref)?.let { return it } }

                // Some generators put the cover href directly in content instead of a
                // manifest id. Accept it as a compatibility fallback.
                resolveMetadataReference(packageHref, coverId)?.let { return it }
            }

        // EPUB 2 legacy guide: the reference usually points to a cover XHTML page, but it can
        // also point directly at an image.
        document.elementsByLocalName("reference")
            .firstOrNull { it.attr("type").equals("cover", ignoreCase = true) }
            ?.attr("href")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { href -> resolveMetadataReference(packageHref, href)?.let { return it } }

        return null
    }

    private fun resolveMetadataItem(item: Element, packageHref: String): String? {
        val href = item.attr("href").trim()
        if (href.isEmpty()) return null

        val entryPath = resolveMetadataHref(packageHref, href) ?: return null
        return if (item.attr("media-type").startsWith("image/", ignoreCase = true)) {
            entryPath
        } else {
            getImagesFromPage(entryPath).firstOrNull()
        }
    }

    private fun resolveMetadataHref(packageHref: String, href: String): String? {
        val path = decodeHrefPath(href)
        if (path.isEmpty()) return null

        val entryPath = resolveZipPath(getParentDirectory(packageHref), path)
        return getInputStream(entryPath)?.use { entryPath }
    }

    private fun decodeHrefPath(href: String): String {
        val rawPath = href.substringBefore('#').substringBefore('?').trim()
        return runCatching {
            URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(rawPath)
    }

    private fun resolveMetadataReference(packageHref: String, href: String): String? {
        val entryPath = resolveMetadataHref(packageHref, href) ?: return null
        return if (isImagePath(entryPath)) {
            entryPath
        } else {
            getImagesFromPage(entryPath).firstOrNull()
        }
    }

    private fun isImagePath(path: String): Boolean {
        return path.substringAfterLast('.', "").lowercase() in setOf(
            "avif",
            "gif",
            "jpeg",
            "jpg",
            "png",
            "svg",
            "webp",
        )
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }
}
