package eu.kanade.domain.manga.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalFolderRelinkPlannerTest {
    private fun old(id: Long, url: String, hash: String? = "same") =
        LocalFolderRelinkPlanner.Existing(id, LocalFolderRelinkPlanner.File(url, hash))
    private fun file(url: String, hash: String? = "same") = LocalFolderRelinkPlanner.File(url, hash)

    @Test
    fun `moving the same files preserves chapter IDs`() {
        val plan = LocalFolderRelinkPlanner.plan(listOf(old(17, "file:///old/01.cbz")), listOf(file("file:///new/01.cbz")))
        assertEquals(17L, plan.matches.single().id)
        assertFalse(plan.matches.single().needsConfirmation)
        assertTrue(plan.retained.isEmpty())
        assertTrue(plan.added.isEmpty())
    }

    @Test
    fun `same filename with different content starts a new chapter and retains the old record`() {
        val plan = LocalFolderRelinkPlanner.plan(listOf(old(17, "file:///old/01.cbz", "a")), listOf(file("file:///new/01.cbz", "b")))
        assertTrue(plan.matches.isEmpty())
        assertEquals(listOf(17L), plan.retained)
        assertEquals(1, plan.added.size)
    }

    @Test
    fun `unavailable old content needs explicit confirmation`() {
        val plan = LocalFolderRelinkPlanner.plan(listOf(old(17, "file:///old/01.cbz", null)), listOf(file("file:///new/01.cbz")))
        assertTrue(plan.matches.single().needsConfirmation)
    }

    @Test
    fun `duplicate old filenames do not guess a record to overwrite`() {
        val plan = LocalFolderRelinkPlanner.plan(
            listOf(old(1, "file:///a/01.cbz"), old(2, "file:///b/01.cbz")),
            listOf(file("file:///new/01.cbz")),
        )
        assertTrue(plan.matches.isEmpty())
        assertEquals(listOf(1L, 2L), plan.retained)
    }

    @Test
    fun `same chapter number or different case does not count as an exact filename`() {
        val plan = LocalFolderRelinkPlanner.plan(listOf(old(17, "file:///old/Vol%2001.cbz")), listOf(file("file:///new/vol%2001.cbz")))
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun `document URI filenames decode Unicode spaces and literal plus signs`() {
        assertEquals("巻 01+.cbz", LocalFolderRelinkPlanner.filename("content://provider/tree/root/document/primary%3Anew%2F%E5%B7%BB%2001+.cbz"))
    }

    @Test
    fun `missing and additional files remain separate`() {
        val plan = LocalFolderRelinkPlanner.plan(listOf(old(17, "file:///old/01.cbz")), listOf(file("file:///new/02.cbz")))
        assertEquals(listOf(17L), plan.retained)
        assertEquals("file:///new/02.cbz", plan.added.single().url)
    }
}
