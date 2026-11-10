package eu.kanade.tachiyomi.ui.manga

import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal

/** Whether a manga's chapters are shown as a grid of covers, as volumes are, or as a list. */
object ChapterLayout {

    /** Series with more entries than this are split by chapter, whatever the entries are called. */
    private const val MAX_VOLUMES = 60

    private const val NUMBER = """[0-9０-９〇一二三四五六七八九十百千]+"""

    private val chapterName = Regex(
        """(?:\b(?:ch|chap|chapter|ep|episode)\.?\s*[0-9]|#\s*[0-9]|第\s*$NUMBER\s*[話话回]|[0-9０-９]\s*[話话])""",
        RegexOption.IGNORE_CASE,
    )

    fun isGrid(manga: Manga, chapterNames: List<String>): Boolean {
        return when (manga.chapterLayout) {
            Manga.CHAPTER_LAYOUT_GRID -> true
            Manga.CHAPTER_LAYOUT_LIST -> false
            else -> manga.isLocal() && looksLikeVolumes(chapterNames)
        }
    }

    fun looksLikeVolumes(chapterNames: List<String>): Boolean {
        if (chapterNames.isEmpty() || chapterNames.size > MAX_VOLUMES) return false
        val chapters = chapterNames.count { chapterName.containsMatchIn(it) }
        return chapters * 2 <= chapterNames.size
    }
}
