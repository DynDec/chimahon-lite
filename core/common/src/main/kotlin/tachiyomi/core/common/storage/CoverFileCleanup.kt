package tachiyomi.core.common.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only explicitly supplied, app-owned cover files are eligible; source images are never modified. */
class CoverFileCleanup(
    private val stat: (File) -> FileState,
    private val createLink: (Path, Path) -> Unit = { link, existing -> Files.createLink(link, existing) },
) {
    data class FileState(
        val identity: String,
        val links: Long,
        val size: Long,
        val allocatedBytes: Long,
        val modified: Long,
        val device: Long,
    )

    data class Result(
        val removed: Int,
        val shared: Int,
        val freedBytes: Long,
        val skipped: Int,
    )

    fun clean(
        candidates: Map<File, FileState>,
        referenced: Set<File>,
        checkCancelled: () -> Unit = {},
    ): Result = synchronized(CoverFileStorage.lock) {
        var removed = 0
        var shared = 0
        var freedBytes = 0L
        var skipped = 0
        val retained = mutableListOf<File>()

        for ((file, original) in candidates) {
            checkCancelled()
            try {
                if (!isRegularFile(file) || !unchanged(original, stat(file))) {
                    skipped++
                    continue
                }
                if (file in referenced) {
                    retained += file
                } else {
                    val before = stat(file)
                    Files.delete(file.toPath())
                    if (before.links == 1L) freedBytes += before.allocatedBytes
                    removed++
                }
            } catch (_: IOException) {
                skipped++
            }
        }

        // Only equal-sized files can be identical. Hash those groups using a bounded buffer.
        val groups = retained.groupBy { candidates.getValue(it).let { state -> state.device to state.size } }
        for (group in groups.values.filter { it.size > 1 }) {
            val byHash = mutableMapOf<String, MutableList<File>>()
            for (file in group) {
                checkCancelled()
                try {
                    if (!isRegularFile(file) || !unchanged(candidates.getValue(file), stat(file))) {
                        skipped++
                        continue
                    }
                    val hash = digest(file, checkCancelled)
                    val matches = byHash.getOrPut(hash) { mutableListOf() }
                    var linked = false
                    for (existing in matches) {
                        if (!isRegularFile(existing)) continue
                        if (Files.isSameFile(existing.toPath(), file.toPath())) {
                            linked = true // Already shared by an earlier cleanup.
                            break
                        }
                        if (!equalBytes(existing, file, checkCancelled)) continue
                        val before = stat(file)
                        // Create the link beside the destination, then atomically replace it.
                        // Unsupported filesystems leave the original cover untouched.
                        val temporary = Files.createTempFile(file.parentFile.toPath(), "cover-link-", ".tmp")
                        try {
                            Files.delete(temporary)
                            createLink(temporary, existing.toPath())
                            checkCancelled()
                            if (!unchanged(before, stat(file)) || !Files.isSameFile(temporary, existing.toPath())) {
                                throw IOException("Cover changed during cleanup")
                            }
                            Files.move(
                                temporary,
                                file.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            if (before.links == 1L) freedBytes += before.allocatedBytes
                            shared++
                            linked = true
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                        break
                    }
                    if (!linked) matches += file
                } catch (_: IOException) {
                    skipped++
                } catch (_: UnsupportedOperationException) {
                    skipped++
                }
            }
        }
        Result(removed, shared, freedBytes, skipped)
    }

    private fun unchanged(before: FileState, after: FileState): Boolean =
        before.identity == after.identity && before.size == after.size && before.modified == after.modified

    private fun isRegularFile(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun digest(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun equalBytes(first: File, second: File, checkCancelled: () -> Unit): Boolean {
        first.inputStream().buffered().use { left ->
            second.inputStream().buffered().use { right ->
                val a = ByteArray(DEFAULT_BUFFER_SIZE)
                val b = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val countA = left.read(a)
                    val countB = right.read(b)
                    if (countA != countB) return false
                    if (countA == -1) return true
                    if (!a.copyOf(countA).contentEquals(b.copyOf(countB))) return false
                }
            }
        }
    }
}
