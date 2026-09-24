package tachiyomi.core.common.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

class CoverFileCleanupTest {
    @TempDir
    lateinit var directory: File

    private val cleaner get() = CoverFileCleanup(::stat)

    @Test
    fun `removes only orphaned candidates and keeps referenced covers and unrelated files`() {
        val live = cover("live.jpg", "active cover")
        val orphan = cover("orphan.jpg", "unused")
        val custom = cover("custom.jpg", "custom cover")
        val temporary = cover("cover.tmp", "in progress")
        val result = cleaner.clean(snapshot(live, orphan, custom), setOf(live, custom))

        assertEquals(1, result.removed)
        assertEquals(6, result.freedBytes)
        assertEquals("active cover", live.readText())
        assertEquals("custom cover", custom.readText())
        assertTrue(temporary.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `identical files share storage without changing paths or bytes and cleanup is idempotent`() {
        val a = cover("a.jpg", "same bytes")
        val b = cover("b.jpg", "same bytes")
        val c = cover("c.cbi", "same bytes")
        val different = cover("different.jpg", "diff bytes")
        val files = arrayOf(a, b, c, different)

        val first = cleaner.clean(snapshot(*files), files.toSet())
        assertEquals(2, first.shared)
        assertEquals(20, first.freedBytes)
        assertTrue(Files.isSameFile(a.toPath(), b.toPath()))
        assertTrue(Files.isSameFile(a.toPath(), c.toPath()))
        assertFalse(Files.isSameFile(a.toPath(), different.toPath()))
        assertEquals("same bytes", b.readText())
        assertEquals("same bytes", c.readText())

        val second = cleaner.clean(snapshot(*files), files.toSet())
        assertEquals(CoverFileCleanup.Result(0, 0, 0, 0), second)
    }

    @Test
    fun `updating one shared cover does not change another and deleting one keeps the other readable`() {
        val a = cover("a.jpg", "original")
        val b = cover("b.jpg", "original")
        cleaner.clean(snapshot(a, b), setOf(a, b))

        CoverFileStorage.write(a) { it.writeText("replacement") }
        assertEquals("original", b.readText())
        assertEquals("replacement", a.readText())
        assertFalse(Files.isSameFile(a.toPath(), b.toPath()))

        CoverFileStorage.write(a) { it.writeText("original") }
        cleaner.clean(snapshot(a, b), setOf(a, b))
        val result = cleaner.clean(snapshot(a, b), setOf(b))
        assertEquals(1, result.removed)
        assertEquals(0, result.freedBytes)
        assertEquals("original", b.readText())
    }

    @Test
    fun `failed writes preserve the previous shared cover and remove temporary output`() {
        val a = cover("a.jpg", "original")
        val b = cover("b.jpg", "original")
        cleaner.clean(snapshot(a, b), setOf(a, b))

        assertThrows(IOException::class.java) {
            CoverFileStorage.write(a) {
                it.writeText("incomplete")
                throw IOException("disk full")
            }
        }
        assertEquals("original", a.readText())
        assertEquals("original", b.readText())
        assertEquals(setOf(a, b), directory.listFiles()!!.toSet())
    }

    @Test
    fun `skips covers replaced after the snapshot including orphan candidates`() {
        val a = cover("a.jpg", "old")
        val b = cover("b.jpg", "old")
        val before = snapshot(a, b)
        CoverFileStorage.write(b) { it.writeText("new cover") }

        val result = cleaner.clean(before, setOf(a))
        assertEquals(1, result.skipped)
        assertEquals(0, result.removed)
        assertEquals("new cover", b.readText())
    }

    @Test
    fun `unreadable files are skipped without deleting referenced covers`() {
        val a = cover("a.jpg", "cover")
        val before = snapshot(a)
        val result = CoverFileCleanup(stat = { throw IOException("unavailable") }).clean(before, setOf(a))
        assertEquals(1, result.skipped)
        assertEquals("cover", a.readText())
    }

    @Test
    fun `cancellation stops cleanup without changing pending covers`() {
        val a = cover("a.jpg", "cover")
        assertThrows(CancellationException::class.java) {
            cleaner.clean(snapshot(a), emptySet()) { throw CancellationException() }
        }
        assertEquals("cover", a.readText())
    }

    @Test
    fun `unsupported sharing leaves both original files intact without temporary files`() {
        val a = cover("a.jpg", "original")
        val b = cover("b.jpg", "original")
        val unsupported = CoverFileCleanup(::stat) { _, _ -> throw UnsupportedOperationException() }

        val result = unsupported.clean(snapshot(a, b), setOf(a, b))
        assertEquals(CoverFileCleanup.Result(0, 0, 0, 1), result)
        assertEquals("original", a.readText())
        assertEquals("original", b.readText())
        assertFalse(Files.isSameFile(a.toPath(), b.toPath()))
        assertEquals(setOf(a, b), directory.listFiles()!!.toSet())
    }

    @Test
    fun `files on different devices are never linked`() {
        val a = cover("a.jpg", "original")
        val b = cover("b.jpg", "original")
        val separateDevices = CoverFileCleanup(stat = { stat(it).copy(device = if (it == a) 1 else 2) })
        val candidates = mapOf(a to stat(a).copy(device = 1), b to stat(b).copy(device = 2))

        assertEquals(CoverFileCleanup.Result(0, 0, 0, 0), separateDevices.clean(candidates, setOf(a, b)))
        assertFalse(Files.isSameFile(a.toPath(), b.toPath()))
    }

    private fun cover(name: String, contents: String) = File(directory, name).apply { writeText(contents) }

    private fun snapshot(vararg files: File) = files.associateWith(::stat)

    private fun stat(file: File): CoverFileCleanup.FileState {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        // Count the real hard links in this fixture; unlike unix:nlink this also works on Windows.
        val links = directory.listFiles()!!.count { Files.isSameFile(it.toPath(), file.toPath()) }.toLong()
        return CoverFileCleanup.FileState(
            identity = attributes.fileKey()?.toString() ?: attributes.creationTime().toString(),
            links = links,
            size = attributes.size(),
            allocatedBytes = attributes.size(),
            modified = attributes.lastModifiedTime().toMillis(),
            device = 0,
        )
    }
}
