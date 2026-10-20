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
    val key = documentKey(first) ?: return false
    return key == documentKey(second)
}

/** Whether [child] names a file below [folder], for both tree documents and plain file URIs. */
fun isFileUriInside(child: String, folder: String): Boolean {
    val childKey = documentKey(child) ?: fileKey(child) ?: return false
    val folderKey = documentKey(folder) ?: fileKey(folder) ?: return false
    if (childKey == folderKey) return false
    // A volume root document id ends with ':' and its children follow it directly, as in "primary:Manga".
    return childKey.startsWith(if (folderKey.endsWith(':')) folderKey else "${folderKey.trimEnd('/')}/")
}

private fun documentKey(url: String): String? {
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

private fun fileKey(url: String): String? {
    if (!url.startsWith("file://")) return null
    return URLDecoder.decode(url.removePrefix("file://").replace("+", "%2B"), "UTF-8").trimEnd('/')
}
