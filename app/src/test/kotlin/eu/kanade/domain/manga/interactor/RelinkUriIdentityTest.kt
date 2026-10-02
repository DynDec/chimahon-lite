package eu.kanade.domain.manga.interactor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.storage.areSameFileUris

class RelinkUriIdentityTest {
    @Test
    fun `same document reached through different tree grants has one identity`() {
        assertTrue(
            areSameFileUris(
                "content://provider/tree/primary%3Alibrary/document/primary%3Alibrary%2FBook",
                "content://provider/tree/primary%3Alibrary%2FBook",
            ),
        )
    }

    @Test
    fun `different providers or document IDs remain distinct`() {
        assertFalse(areSameFileUris("content://first/tree/book", "content://second/tree/book"))
        assertFalse(areSameFileUris("content://provider/tree/book1", "content://provider/tree/book2"))
    }

    @Test
    fun `unrelated URIs cannot compare equal through a missing document key`() {
        assertFalse(areSameFileUris("file:///old/Book", "file:///new/Book"))
        assertFalse(areSameFileUris("content://provider/other/Book", "content://provider/other2/Book"))
    }
}
