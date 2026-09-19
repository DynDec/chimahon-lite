package tachiyomi.source.local.io

import com.hippo.unifile.UniFile

expect class LocalSourceFileSystem {

    fun getBaseDirectory(): UniFile?

    fun getFilesInBaseDirectory(): List<UniFile>

    fun getMangaEntry(url: String): UniFile?

    fun getMangaDirectory(name: String): UniFile?

    fun getFilesInMangaDirectory(name: String): List<UniFile>

    fun getChapterFile(url: String): UniFile?

    fun getChapterFile(mangaUrl: String, chapterUrl: String): UniFile?

    fun usesDefaultBaseDirectory(): Boolean
}
