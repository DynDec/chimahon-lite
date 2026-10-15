package tachiyomi.source.local.image

import android.net.Uri

/** Thumbnail URL that names an image inside a local archive: `chimahon-cover://archive?file=…&entry=…`. */
object LocalCoverReference {
    const val SCHEME = "chimahon-cover"
    private const val AUTHORITY = "archive"
    private const val FILE = "file"
    private const val ENTRY = "entry"
    private const val VERSION = "v"

    /** Bump when cover selection changes so stored references are resolved again. */
    private const val CURRENT_VERSION = "2"
    private val VERSION_PARAMETER = Regex("[?&]$VERSION=([^&#]*)")

    data class Target(val fileUri: String, val entry: String)

    fun create(fileUri: String, entry: String): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendQueryParameter(FILE, fileUri)
        .appendQueryParameter(ENTRY, entry)
        .appendQueryParameter(VERSION, CURRENT_VERSION)
        .build()
        .toString()

    fun isReference(url: String?): Boolean = url?.startsWith("$SCHEME:") == true

    /** References written by an older cover selection, which should be resolved again. */
    fun isOutdated(url: String?): Boolean =
        isReference(url) && VERSION_PARAMETER.find(url!!)?.groupValues?.get(1) != CURRENT_VERSION

    fun parse(url: String?): Target? {
        if (!isReference(url)) return null
        val uri = Uri.parse(url)
        return Target(
            fileUri = uri.getQueryParameter(FILE) ?: return null,
            entry = uri.getQueryParameter(ENTRY) ?: return null,
        )
    }

    /** Covers that older versions extracted into the app cache's `local_covers` directory. */
    fun isLegacyGeneratedCover(url: String?): Boolean = url?.contains("/$LEGACY_DIRECTORY/cover-") == true

    const val LEGACY_DIRECTORY = "local_covers"
}
