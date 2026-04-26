package com.securevaultoffline.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

object SafFolderScanner {

    private val CHILD_COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    /**
     * Walks the SAF tree on a background dispatcher. Calls [onProgress] on the main thread
     * with the number of files found so far (throttled) so the UI can update without freezing.
     */
    suspend fun listFilesRecursive(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (fileCount: Int) -> Unit = {},
    ): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<Pair<Uri, String>>(512)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to "")

        while (stack.isNotEmpty() && isActive) {
            val (parentDocId, pathPrefix) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null) ?: continue
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    if (!isActive) return@withContext out
                    val docId = c.getString(idCol) ?: continue
                    val name = c.getString(nameCol) ?: continue
                    val mime = c.getString(mimeCol) ?: continue
                    val rel = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                    when (mime) {
                        DocumentsContract.Document.MIME_TYPE_DIR -> {
                            stack.addLast(docId to rel)
                        }
                        else -> {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            out.add(docUri to rel)
                            val n = out.size
                            if (n == 1 || n % 32 == 0) {
                                withContext(Dispatchers.Main) { onProgress(n) }
                                yield()
                            }
                        }
                    }
                }
            }
        }
        withContext(Dispatchers.Main) { onProgress(out.size) }
        out
    }
}
