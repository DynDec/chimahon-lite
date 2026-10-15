package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipIndexTest {
    private val page = ByteArray(40_000) { (it % 251).toByte() }

    @Test
    fun `lists stored and deflated entries in archive order and reads them`() {
        val bytes = zip {
            putNextEntry(ZipEntry("chapter/"))
            closeEntry()
            putNextEntry(ZipEntry("chapter/002.jpg"))
            write(page)
            closeEntry()
            putNextEntry(stored("chapter/001.png", page))
            write(page)
            closeEntry()
        }
        val index = index(bytes)!!

        assertEquals(listOf("chapter/", "chapter/002.jpg", "chapter/001.png"), index.entries.map { it.name })
        assertTrue(index.entries[0].isDirectory)
        assertArrayEquals(page, index.read(index.find("chapter/002.jpg")!!))
        assertArrayEquals(page, index.read(index.find("chapter/001.png")!!))
        assertNull(index.find("missing.jpg"))
    }

    @Test
    fun `reading one entry touches only the directory and that entry`() {
        val filler = ByteArray(2_000_000) { (it * 31).toByte() }
        val bytes = zip {
            putNextEntry(ZipEntry("cover.jpg"))
            write(page)
            closeEntry()
            putNextEntry(stored("filler.bin", filler))
            write(filler)
            closeEntry()
        }
        var touched = 0L
        val index = ZipIndex.load(bytes.size.toLong()) { position, buffer, offset, length ->
            touched += length
            readAt(bytes, position, buffer, offset, length)
        }!!
        index.read(index.find("cover.jpg")!!)

        assertTrue(touched < 200_000) { "read $touched of ${bytes.size} bytes" }
    }

    @Test
    fun `non UTF-8 names fall back to Shift_JIS`() {
        val name = "百合/表紙.jpg"
        val bytes = zip(charset = charset("Shift_JIS")) {
            putNextEntry(ZipEntry(name))
            write(page)
            closeEntry()
        }

        assertEquals(name, index(bytes)!!.entries.single().name)
    }

    @Test
    fun `encrypted entries are listed but not readable`() {
        val bytes = zip {
            putNextEntry(ZipEntry("001.jpg"))
            write(page)
            closeEntry()
        }
        // Set the general purpose "encrypted" bit in the central directory header.
        val central = bytes.indexOfSignature(0x02014b50)
        bytes[central + 8] = (bytes[central + 8].toInt() or 1).toByte()
        val entry = index(bytes)!!.entries.single()

        assertTrue(entry.isEncrypted)
        assertFalse(entry.isReadable)
    }

    @Test
    fun `non ZIP data, truncated archives and ZIP64 markers are rejected for fallback`() {
        assertNull(index(ByteArray(4096) { 7 }))
        assertNull(index(ByteArray(0)))
        val bytes = zip {
            putNextEntry(ZipEntry("001.jpg"))
            write(page)
            closeEntry()
        }
        assertNull(index(bytes.copyOfRange(0, bytes.size - 30)))

        val zip64 = bytes.copyOf()
        val end = zip64.indexOfSignature(0x06054b50)
        for (i in 16 until 20) zip64[end + i] = 0xff.toByte() // Directory offset stored in ZIP64 record.
        assertNull(index(zip64))
    }

    private fun index(bytes: ByteArray) = ZipIndex.load(bytes.size.toLong()) { position, buffer, offset, length ->
        readAt(bytes, position, buffer, offset, length)
    }

    private fun readAt(bytes: ByteArray, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    private fun stored(name: String, data: ByteArray) = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        crc = CRC32().also { it.update(data) }.value
    }

    private fun zip(charset: java.nio.charset.Charset = Charsets.UTF_8, block: ZipOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { output -> ZipOutputStream(output, charset).use(block) }.toByteArray()

    private fun ByteArray.indexOfSignature(signature: Int): Int = (0..size - 4).first { i ->
        (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8) or
            ((this[i + 2].toInt() and 0xff) shl 16) or ((this[i + 3].toInt() and 0xff) shl 24) == signature
    }
}
