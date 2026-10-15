package mihon.core.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/**
 * Reads a ZIP central directory with positioned reads, so listing entries and reading one entry only
 * touches the directory and that entry. Sequential header scans fault in most of a large archive.
 *
 * Returns `null` from [load] for anything it cannot index (not a ZIP, ZIP64, multi-disk); callers fall
 * back to [ArchiveReader]. Encrypted or unsupported entries are listed but not readable here.
 */
class ZipIndex private constructor(
    private val read: PositionedRead,
    val entries: List<Entry>,
) {
    /** Reads up to `length` bytes at `position` into `buffer` from `offset`; returns the count, or -1 at the end. */
    fun interface PositionedRead {
        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    }

    class Entry internal constructor(
        val name: String,
        internal val flags: Int,
        internal val method: Int,
        internal val compressedSize: Long,
        val size: Long,
        internal val localHeaderOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith('/')
        val isEncrypted: Boolean get() = flags and FLAG_ENCRYPTED != 0

        /** Whether [ZipIndex.read] can return this entry's bytes. */
        val isReadable: Boolean
            get() = !isEncrypted && !isDirectory && (method == METHOD_STORED || method == METHOD_DEFLATED) &&
                compressedSize != ZIP64_MARKER && size != ZIP64_MARKER && size <= MAX_ENTRY_SIZE
    }

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }

    fun read(entry: Entry): ByteArray {
        if (!entry.isReadable) throw IOException("Unsupported ZIP entry ${entry.name}")
        val header = readFully(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (header.getInt(0) != LOCAL_HEADER_SIGNATURE) throw IOException("Bad local header for ${entry.name}")
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE + header.unsignedShort(26) + header.unsignedShort(28)
        val data = ByteArray(entry.compressedSize.toInt())
        readFully(dataStart, data)
        if (entry.method == METHOD_STORED) return data

        val output = ByteArray(entry.size.toInt())
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            var count = 0
            while (count < output.size) {
                val inflated = inflater.inflate(output, count, output.size - count)
                if (inflated == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                count += inflated
            }
            if (count != output.size) throw IOException("Truncated ZIP entry ${entry.name}")
        } finally {
            inflater.end()
        }
        return output
    }

    private fun readFully(position: Long, length: Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(length).also { readFully(position, it) }).order(ByteOrder.LITTLE_ENDIAN)

    private fun readFully(position: Long, target: ByteArray) = readFully(read, position, target)

    companion object {
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val END_SIGNATURE = 0x06054b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_SIZE = 22
        private const val MAX_COMMENT = 0xffff
        private const val FLAG_ENCRYPTED = 0x1
        private const val FLAG_UTF8 = 0x800
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val ZIP64_MARKER = 0xffffffffL
        private const val MAX_ENTRY_SIZE = Int.MAX_VALUE - 8L
        private val SHIFT_JIS: Charset? = runCatching { Charset.forName("Shift_JIS") }.getOrNull()

        /** Indexes the archive of [size] bytes, or returns `null` when it is not a plain ZIP. */
        fun load(size: Long, read: PositionedRead): ZipIndex? {
            if (size < END_SIZE) return null
            return try {
                val tailLength = minOf(size, (END_SIZE + MAX_COMMENT).toLong()).toInt()
                val tailStart = size - tailLength
                val tail = ByteBuffer.wrap(ByteArray(tailLength).also { readFully(read, tailStart, it) })
                    .order(ByteOrder.LITTLE_ENDIAN)
                val end = (tailLength - END_SIZE downTo 0).firstOrNull { tail.getInt(it) == END_SIGNATURE }
                    ?: return null
                // ZIP64 archives keep their real sizes elsewhere; leave them to libarchive.
                if (end >= 20 && tail.getInt(end - 20) == ZIP64_LOCATOR_SIGNATURE) return null
                if (tail.unsignedShort(end + 4) != 0 || tail.unsignedShort(end + 6) != 0) return null
                val count = tail.unsignedShort(end + 10)
                val directorySize = tail.getInt(end + 12).toLong() and ZIP64_MARKER
                val directoryOffset = tail.getInt(end + 16).toLong() and ZIP64_MARKER
                if (directoryOffset + directorySize > tailStart + end) return null

                val directory = ByteBuffer.wrap(ByteArray(directorySize.toInt()).also { readFully(read, directoryOffset, it) })
                    .order(ByteOrder.LITTLE_ENDIAN)
                var position = 0
                val entries = List(count) {
                    if (position + CENTRAL_HEADER_SIZE > directory.limit() ||
                        directory.getInt(position) != CENTRAL_HEADER_SIGNATURE
                    ) {
                        return null
                    }
                    val flags = directory.unsignedShort(position + 8)
                    val nameLength = directory.unsignedShort(position + 28)
                    val extraLength = directory.unsignedShort(position + 30)
                    val commentLength = directory.unsignedShort(position + 32)
                    val nameBytes = ByteArray(nameLength)
                    directory.position(position + CENTRAL_HEADER_SIZE)
                    directory.get(nameBytes)
                    val entry = Entry(
                        name = decodeName(nameBytes, flags),
                        flags = flags,
                        method = directory.unsignedShort(position + 10),
                        compressedSize = directory.getInt(position + 20).toLong() and ZIP64_MARKER,
                        size = directory.getInt(position + 24).toLong() and ZIP64_MARKER,
                        localHeaderOffset = directory.getInt(position + 42).toLong() and ZIP64_MARKER,
                    )
                    position += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
                    entry
                }
                ZipIndex(read, entries)
            } catch (_: IOException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: java.nio.BufferUnderflowException) {
                null
            }
        }

        private fun decodeName(bytes: ByteArray, flags: Int): String {
            if (flags and FLAG_UTF8 != 0) return String(bytes, Charsets.UTF_8)
            // Archives without the UTF-8 flag are usually UTF-8 anyway; Japanese tools often use Shift_JIS.
            return strictDecode(bytes, Charsets.UTF_8)
                ?: SHIFT_JIS?.let { strictDecode(bytes, it) }
                ?: String(bytes, Charsets.ISO_8859_1)
        }

        private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        private fun readFully(read: PositionedRead, position: Long, target: ByteArray) {
            var offset = 0
            while (offset < target.size) {
                val count = read.read(position + offset, target, offset, target.size - offset)
                if (count <= 0) throw IOException("Unexpected end of ZIP data")
                offset += count
            }
        }

        private fun ByteBuffer.unsignedShort(index: Int): Int = getShort(index).toInt() and 0xffff
    }
}
