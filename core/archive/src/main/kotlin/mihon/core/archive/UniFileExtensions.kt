package mihon.core.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.hippo.unifile.UniFile
import java.io.IOException

internal fun UniFile.openFileDescriptor(context: Context, mode: String): ParcelFileDescriptor =
    context.contentResolver.openFileDescriptor(uri, mode) ?: error("Failed to open file descriptor: ${filePath ?: uri}")

fun UniFile.archiveReader(context: Context) = openFileDescriptor(context, "r").use { ArchiveReader(it) }

fun UniFile.epubReader(context: Context) = EpubReader(archiveReader(context))

/** Entry names in archive order, read from the ZIP central directory when possible. */
fun UniFile.archiveEntries(context: Context): List<ArchiveEntry> = openFileDescriptor(context, "r").use { pfd ->
    pfd.zipIndex()?.entries?.map { ArchiveEntry(it.name, !it.isDirectory, it.isEncrypted) }
        ?: ArchiveReader(pfd).use { reader -> reader.useEntries { it.toList() } }
}

/** Reads one entry fully into memory, touching only that entry for unencrypted ZIP-based files. */
fun UniFile.readArchiveEntry(context: Context, name: String): ByteArray? = openFileDescriptor(context, "r").use { pfd ->
    val index = pfd.zipIndex()
    val entry = index?.find(name)
    when {
        index != null && entry == null -> null
        index != null && entry!!.isReadable -> index.read(entry)
        else -> ArchiveReader(pfd).use { reader -> reader.getInputStream(name)?.use { it.readBytes() } }
    }
}

internal fun ParcelFileDescriptor.zipIndex(): ZipIndex? = ZipIndex.load(statSize) { position, buffer, offset, length ->
    try {
        Os.pread(fileDescriptor, buffer, offset, length, position).let { if (it == 0) -1 else it }
    } catch (e: ErrnoException) {
        throw IOException(e)
    }
}
