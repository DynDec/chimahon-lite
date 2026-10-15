package tachiyomi.source.local.image

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.ArchiveEntry

object LocalCoverSelection {
    /**
     * The first image in natural name order. [isImage] must sniff names it does not recognize
     * (`.jpeg`, `.JPG`, no extension) rather than reject them, so a `cover.jpeg` is not passed over.
     */
    fun firstImage(entries: List<ArchiveEntry>, isImage: (String) -> Boolean): String? = entries
        .filter { it.isFile }
        .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
        .firstOrNull { isImage(it.name) }
        ?.name
}
