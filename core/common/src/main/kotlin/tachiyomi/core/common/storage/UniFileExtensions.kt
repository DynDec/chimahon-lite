package tachiyomi.core.common.storage

import com.hippo.unifile.UniFile
import java.net.URLDecoder

val UniFile.extension: String?
    get() = name?.substringAfterLast('.')

val UniFile.nameWithoutExtension: String?
    get() = name?.substringBeforeLast('.')

val UniFile.displayablePath: String
    get() = filePath ?: uri.toString()

/** A document can be exposed through different persisted tree permissions. */
fun UniFile.isSameFileAs(other: UniFile): Boolean {
    return areSameFileUris(uri.toString(), other.uri.toString()) || (filePath != null && filePath == other.filePath)
}

fun areSameFileUris(first: String, second: String): Boolean {
    if (first == second) return true
    fun documentKey(url: String): String? {
        if (!url.startsWith("content://")) return null
        val marker = when {
            "/document/" in url -> "/document/"
            "/tree/" in url -> "/tree/"
            else -> return null
        }
        val authority = url.removePrefix("content://").substringBefore('/')
        val id = URLDecoder.decode(url.substringAfterLast(marker).substringBefore('/').replace("+", "%2B"), "UTF-8")
        return "$authority:$id"
    }
    val key = documentKey(first) ?: return false
    return key == documentKey(second)
}
