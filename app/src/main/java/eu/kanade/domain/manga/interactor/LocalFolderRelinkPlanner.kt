package eu.kanade.domain.manga.interactor

import java.net.URLDecoder

/** Match only unique, exact filenames. Chapter numbers and display titles are not identities. */
internal object LocalFolderRelinkPlanner {
    data class File(val url: String, val fingerprint: String?)
    data class Existing(val id: Long, val file: File)
    data class Match(val id: Long, val file: File, val needsConfirmation: Boolean)
    data class Plan(val matches: List<Match>, val retained: List<Long>, val added: List<File>)

    fun filename(url: String): String = URLDecoder.decode(
        url.substringAfterLast('/').replace("+", "%2B"),
        "UTF-8",
    ).substringAfterLast('/')

    fun plan(existing: List<Existing>, destination: List<File>): Plan {
        val oldByName = existing.groupBy { filename(it.file.url) }
        val newByName = destination.groupBy { filename(it.url) }
        val matches = destination.mapNotNull { file ->
            val name = filename(file.url)
            val old = oldByName[name]?.singleOrNull() ?: return@mapNotNull null
            if (newByName[name]?.size != 1) return@mapNotNull null
            val before = old.file.fingerprint
            val after = file.fingerprint
            if (before != null && after != null && before != after) return@mapNotNull null
            Match(old.id, file, needsConfirmation = before == null || after == null)
        }
        return Plan(
            matches,
            existing.map { it.id }.filterNot { id -> matches.any { it.id == id } },
            destination.filterNot { file -> matches.any { it.file.url == file.url } },
        )
    }
}
