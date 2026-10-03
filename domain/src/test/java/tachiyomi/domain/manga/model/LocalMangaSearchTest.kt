package tachiyomi.domain.manga.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalMangaSearchTest {

    @Test
    fun `filename and metadata title remain searchable`() {
        assertTrue(matches("folder", filename = "Old Folder", title = "New Title"))
        assertTrue(matches("new TITLE", filename = "Old Folder", title = "New Title"))
    }

    @Test
    fun `author and artist match without a title match`() {
        assertTrue(matches("Writer", author = "The Writer"))
        assertTrue(matches("illustrator", artist = "An Illustrator"))
    }

    @Test
    fun `unicode names and genres match literally`() {
        assertTrue(matches("藤本", author = "藤本タツキ"))
        assertTrue(matches("fantasy", genres = "Action, Fantasy"))
        assertTrue(matches("[story]", title = "A [story]"))
        assertFalse(matches(".*", title = "A story"))
    }

    @Test
    fun `surrounding whitespace is ignored and blank queries include every entry`() {
        assertTrue(matches("  title  ", title = "Title"))
        assertTrue(matches(" \t "))
    }

    @Test
    fun `missing metadata and unrelated terms do not match`() {
        assertFalse(matches("unknown"))
        assertFalse(matches("Other Writer", author = "Known Writer"))
    }

    private fun matches(
        query: String,
        filename: String = "File",
        title: String? = null,
        author: String? = null,
        artist: String? = null,
        genres: String? = null,
    ) = matchesLocalMangaSearch(query, filename, title, author, artist, genres)
}
