package app.ledger.transfer.data

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class P29TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        storageRoot = requireNotNull(context).cacheDir.resolve("p29-provider")
        storage().mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_TITLE, "P29 test provider")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, storage().getUsableSpace())
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        enforceAvailable()
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { include(file(documentId), documentId) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        enforceAvailable()
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            storage().listFiles().orEmpty().sortedBy { it.name }.forEach { include(it, "$ROOT_ID/${it.name}") }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = parentDocumentId == ROOT_ID && documentId.startsWith("$ROOT_ID/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        enforceAvailable()
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        enforceAvailable()
        require(parentDocumentId == ROOT_ID)
        val target = storage().resolve(displayName)
        if (!target.createNewFile()) throw FileNotFoundException(displayName)
        return "$ROOT_ID/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        enforceAvailable()
        if (!file(documentId).delete()) throw FileNotFoundException(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        enforceAvailable()
        val source = file(documentId)
        val target = storage().resolve(displayName)
        if (!source.renameTo(target)) throw FileNotFoundException(documentId)
        return "$ROOT_ID/$displayName"
    }

    private fun MatrixCursor.include(target: File, documentId: String) {
        if (!target.exists()) throw FileNotFoundException(documentId)
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_ID) "P29" else target.name)
            add(Document.COLUMN_SIZE, if (target.isFile) target.length() else null)
            add(Document.COLUMN_MIME_TYPE, if (target.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            add(Document.COLUMN_LAST_MODIFIED, target.lastModified())
            add(
                Document.COLUMN_FLAGS,
                if (target.isDirectory) {
                    Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_WRITE
                } else {
                    Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                },
            )
        }
    }

    private fun file(documentId: String): File = if (documentId == ROOT_ID) storage() else storage().resolve(documentId.substringAfter('/'))
    private fun storage(): File = requireNotNull(storageRoot)
    private fun enforceAvailable() {
        if (permissionRevoked) throw SecurityException("P29 permission revoked")
    }

    companion object {
        const val AUTHORITY = "app.ledger.transfer.data.p29documents"
        const val ROOT_ID = "root"

        @Volatile var permissionRevoked: Boolean = false

        @Volatile var storageRoot: File? = null
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
