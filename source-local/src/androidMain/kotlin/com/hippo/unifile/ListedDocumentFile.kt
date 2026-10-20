package com.hippo.unifile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream
import java.io.OutputStream

/**
 * A tree document that keeps the type, size and modification time its folder's listing returned.
 *
 * A plain [TreeDocumentFile] answers each of those with its own Storage Access Framework query, so checking every
 * listed entry costs several queries per entry. Listing through [listFiles] costs one query per folder, and the
 * children it returns list their own folders the same way.
 *
 * It is in UniFile's package because UniFile's constructor is package-private.
 */
class ListedDocumentFile private constructor(
    parent: UniFile,
    private val context: Context,
    private val document: TreeDocumentFile,
    private val listedName: String?,
    private val listedType: String?,
    private val listedLastModified: Long,
    private val listedLength: Long,
) : UniFile(parent) {

    override fun getUri(): Uri = document.uri

    override fun getName(): String? = listedName ?: document.name

    override fun getType(): String? = listedType ?: document.type

    override fun getFilePath(): String? = document.filePath

    override fun isDirectory(): Boolean = listedType == Document.MIME_TYPE_DIR

    override fun isFile(): Boolean = listedType != null && listedType != Document.MIME_TYPE_DIR

    override fun lastModified(): Long = listedLastModified

    override fun length(): Long = if (isDirectory) -1L else listedLength

    override fun listFiles(): Array<UniFile>? {
        if (!isDirectory) return null
        return listFiles(context, this) ?: document.listFiles()
    }

    override fun listFiles(filter: FilenameFilter?): Array<UniFile>? {
        val files = listFiles() ?: return null
        return if (filter == null) files else files.filter { filter.accept(this, it.name) }.toTypedArray()
    }

    override fun findFile(displayName: String?): UniFile? = document.findFile(displayName)

    override fun createFile(displayName: String?): UniFile? = document.createFile(displayName)

    override fun createDirectory(displayName: String?): UniFile? = document.createDirectory(displayName)

    override fun canRead(): Boolean = document.canRead()

    override fun canWrite(): Boolean = document.canWrite()

    override fun delete(): Boolean = document.delete()

    override fun exists(): Boolean = document.exists()

    override fun renameTo(displayName: String?): Boolean = document.renameTo(displayName)

    override fun openOutputStream(): OutputStream = document.openOutputStream()

    override fun openOutputStream(append: Boolean): OutputStream = document.openOutputStream(append)

    override fun openInputStream(): InputStream = document.openInputStream()

    override fun createRandomAccessFile(mode: String?): UniRandomAccessFile = document.createRandomAccessFile(mode)

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )

        /** [folder]'s children from one query, or null when it is not a tree document or the query fails. */
        fun listFiles(context: Context, folder: UniFile): Array<UniFile>? {
            if (folder !is TreeDocumentFile && folder !is ListedDocumentFile) return null
            return try {
                val uri = folder.uri
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri,
                    DocumentsContract.getDocumentId(uri),
                )
                context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                    val children = ArrayList<UniFile>(cursor.count)
                    while (cursor.moveToNext()) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                        val name = cursor.getString(1)
                        children += ListedDocumentFile(
                            parent = folder,
                            context = context,
                            document = TreeDocumentFile(folder, context, childUri, name),
                            listedName = name,
                            listedType = cursor.getString(2),
                            listedLastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            listedLength = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                        )
                    }
                    children.toTypedArray()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
