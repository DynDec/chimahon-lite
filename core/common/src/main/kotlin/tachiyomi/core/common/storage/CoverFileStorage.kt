package tachiyomi.core.common.storage

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Cover files may share an inode after cleanup. Always replace them, never overwrite in place. */
object CoverFileStorage {
    val lock = Any()

    fun delete(file: File): Boolean = synchronized(lock) { file.delete() }

    fun write(target: File, write: (File) -> Unit) {
        val directory = requireNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create cover directory")
        }
        val temporary = File.createTempFile("cover-", ".tmp", directory)
        try {
            write(temporary)
            replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun replace(temporary: File, target: File) = synchronized(lock) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}
