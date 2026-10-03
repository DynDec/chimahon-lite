package tachiyomi.domain.manga.model

/** Literal, case-insensitive matching across local filenames and available manga metadata. */
fun matchesLocalMangaSearch(
    query: String,
    filenameTitle: String,
    metadataTitle: String?,
    author: String?,
    artist: String?,
    genres: String?,
): Boolean {
    val term = query.trim()
    return term.isEmpty() || sequenceOf(filenameTitle, metadataTitle, author, artist, genres)
        .filterNotNull()
        .any { it.contains(term, ignoreCase = true) }
}
