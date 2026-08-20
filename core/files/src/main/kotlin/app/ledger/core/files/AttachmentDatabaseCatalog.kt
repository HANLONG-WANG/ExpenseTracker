@file:Suppress("LongParameterList", "MagicNumber")

package app.ledger.core.files

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.LedgerDatabase
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.AttachmentStatus
import app.ledger.finance.domain.BlobGcReason
import app.ledger.finance.domain.BlobId
import app.ledger.finance.domain.Hash256
import java.nio.ByteBuffer
import java.time.Instant

internal class AttachmentDatabaseCatalog(
    private val database: LedgerDatabase,
) {
    fun findBlob(plaintextHash: Hash256, plaintextSize: Long): StoredBlob? = database.readLedger { connection ->
        connection.query(
            "SELECT id, uid, storage_name, plaintext_sha256, plaintext_size, mime_type, extension, " +
                "wrapped_data_key, encryption_version, created_at FROM encrypted_blob " +
                "WHERE plaintext_sha256 = ? AND plaintext_size = ?",
            arrayOf(plaintextHash.bytes, plaintextSize),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.storedBlob() else null }
    }

    fun createNew(
        blobId: BlobId,
        attachmentId: AttachmentId,
        storageName: String,
        plaintextHash: Hash256,
        plaintextSize: Long,
        mimeType: String,
        extension: String?,
        wrappedDataKey: ByteArray,
        encryptionVersion: Int,
        displayName: String,
        importedAt: Instant,
    ): StoredAttachmentObject = database.inLedgerTransaction { connection ->
        check(findBlobInTransaction(connection, plaintextHash, plaintextSize) == null) { "attachment blob already exists" }
        val blobInternalId = connection.allocateInternalId("encrypted_blob", blobId.value)
        val attachmentInternalId = connection.allocateInternalId("attachment", attachmentId.value)
        connection.execSQL(
            "INSERT INTO encrypted_blob(" +
                "id,uid,storage_name,plaintext_sha256,plaintext_size,mime_type,extension,wrapped_data_key," +
                "encryption_version,reference_count_projection,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                blobInternalId,
                blobId.value.bytes,
                storageName,
                plaintextHash.bytes,
                plaintextSize,
                mimeType,
                extension,
                wrappedDataKey,
                encryptionVersion,
                1L,
                importedAt.toEpochMilli(),
            ),
        )
        connection.insertAttachment(attachmentInternalId, attachmentId, blobInternalId, displayName, importedAt)
        StoredAttachmentObject(
            attachmentId,
            blobId,
            storageName,
            plaintextHash,
            plaintextSize,
            mimeType,
            extension,
            wrappedDataKey,
            encryptionVersion,
            displayName,
            importedAt,
        )
    }

    fun createAttachmentForExisting(
        blob: StoredBlob,
        attachmentId: AttachmentId,
        displayName: String,
        importedAt: Instant,
    ): StoredAttachmentObject = database.inLedgerTransaction { connection ->
        val current = findBlobInTransaction(connection, blob.plaintextHash, blob.plaintextSize)
            ?: error("attachment blob disappeared during import")
        check(current.blobId == blob.blobId && current.storageName == blob.storageName) {
            "attachment blob identity changed during import"
        }
        val attachmentInternalId = connection.allocateInternalId("attachment", attachmentId.value)
        connection.insertAttachment(attachmentInternalId, attachmentId, blob.internalId, displayName, importedAt)
        connection.execSQL(
            "UPDATE encrypted_blob SET reference_count_projection = " +
                "(SELECT COUNT(*) FROM attachment WHERE blob_id = ?) WHERE id = ?",
            arrayOf(blob.internalId, blob.internalId),
        )
        current.toAttachment(attachmentId, displayName, importedAt)
    }

    fun attachment(attachmentId: AttachmentId): StoredAttachmentObject? = database.readLedger { connection ->
        connection.query(
            "SELECT a.uid AS attachment_uid,a.display_name,a.imported_at,b.uid AS blob_uid,b.storage_name," +
                "b.plaintext_sha256,b.plaintext_size,b.mime_type,b.extension,b.wrapped_data_key,b.encryption_version " +
                "FROM attachment a JOIN encrypted_blob b ON b.id = a.blob_id WHERE a.uid = ?",
            arrayOf(attachmentId.value.bytes),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.storedAttachment() else null }
    }

    fun activeAttachments(): List<StoredAttachmentObject> = database.readLedger { connection ->
        connection.query(
            "SELECT a.uid AS attachment_uid,a.display_name,a.imported_at,b.uid AS blob_uid,b.storage_name," +
                "b.plaintext_sha256,b.plaintext_size,b.mime_type,b.extension,b.wrapped_data_key,b.encryption_version " +
                "FROM attachment a JOIN encrypted_blob b ON b.id = a.blob_id " +
                "WHERE a.status = ? ORDER BY a.imported_at DESC,a.id DESC",
            arrayOf(AttachmentStatus.ACTIVE.ordinal),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.storedAttachment()) } }
    }

    fun rename(attachmentId: AttachmentId, displayName: String): Boolean = database.inLedgerTransaction { connection ->
        connection.compileStatement("UPDATE attachment SET display_name = ? WHERE uid = ?").use { statement ->
            statement.bindString(1, displayName)
            statement.bindBlob(2, attachmentId.value.bytes)
            statement.executeUpdateDelete() == 1
        }
    }

    fun discardUnreferencedAttachment(
        attachmentId: AttachmentId,
        eligibleAfter: Instant,
    ): BlobId? = database.inLedgerTransaction { connection ->
        val row = connection.query(
            "SELECT a.id,a.blob_id,b.uid FROM attachment a JOIN encrypted_blob b ON b.id = a.blob_id WHERE a.uid = ?",
            arrayOf(attachmentId.value.bytes),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                Triple(cursor.getLong(0), cursor.getLong(1), cursor.stableId(2))
            } else {
                null
            }
        } ?: return@inLedgerTransaction null
        val revisionReferences = connection.long(
            "SELECT COUNT(*) FROM transaction_revision_attachment WHERE attachment_id = ?",
            arrayOf(row.first),
        )
        check(revisionReferences == 0L) { "attachment is retained by transaction history" }
        connection.execSQL("DELETE FROM attachment WHERE id = ?", arrayOf(row.first))
        val attachmentReferences = connection.long("SELECT COUNT(*) FROM attachment WHERE blob_id = ?", arrayOf(row.second))
        connection.execSQL(
            "UPDATE encrypted_blob SET reference_count_projection = ? WHERE id = ?",
            arrayOf(attachmentReferences, row.second),
        )
        if (attachmentReferences == 0L) {
            connection.execSQL(
                "INSERT OR REPLACE INTO blob_gc_candidate(blob_id,eligible_after,reason,last_checked_at) VALUES(?,?,?,NULL)",
                arrayOf(row.second, eligibleAfter.toEpochMilli(), BlobGcReason.NO_CURRENT_REFERENCE.ordinal.toLong()),
            )
        }
        BlobId(row.third)
    }

    fun eligibleGarbage(now: Instant): List<StoredBlob> = database.readLedger { connection ->
        connection.query(
            "SELECT b.id,b.uid,b.storage_name,b.plaintext_sha256,b.plaintext_size,b.mime_type,b.extension," +
                "b.wrapped_data_key,b.encryption_version,b.created_at FROM blob_gc_candidate g " +
                "JOIN encrypted_blob b ON b.id = g.blob_id WHERE g.eligible_after <= ? ORDER BY g.eligible_after,b.id",
            arrayOf(now.toEpochMilli()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.storedBlob()) } }
    }

    fun deleteBlobMetadataIfUnreferenced(blob: StoredBlob, checkedAt: Instant): Boolean = database.inLedgerTransaction { connection ->
        val attachmentReferences = connection.long("SELECT COUNT(*) FROM attachment WHERE blob_id = ?", arrayOf(blob.internalId))
        val historicalReferences = connection.long(
            "SELECT COUNT(*) FROM transaction_revision_attachment tra " +
                "JOIN attachment a ON a.id = tra.attachment_id WHERE a.blob_id = ?",
            arrayOf(blob.internalId),
        )
        val backupReferences = connection.long(
            "SELECT COUNT(*) FROM backup_object WHERE content_hash = ? AND size_bytes = ?",
            arrayOf(blob.plaintextHash.bytes, blob.plaintextSize),
        )
        if (attachmentReferences != 0L || historicalReferences != 0L || backupReferences != 0L) {
            connection.execSQL(
                "UPDATE blob_gc_candidate SET last_checked_at = ? WHERE blob_id = ?",
                arrayOf(checkedAt.toEpochMilli(), blob.internalId),
            )
            false
        } else {
            connection.execSQL("DELETE FROM encrypted_blob WHERE id = ?", arrayOf(blob.internalId))
            true
        }
    }

    fun referencedStorageNames(): Set<String> = database.readLedger { connection ->
        connection.query("SELECT storage_name FROM encrypted_blob").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun findBlobInTransaction(
        connection: SupportSQLiteDatabase,
        plaintextHash: Hash256,
        plaintextSize: Long,
    ): StoredBlob? = connection.query(
        "SELECT id,uid,storage_name,plaintext_sha256,plaintext_size,mime_type,extension,wrapped_data_key," +
            "encryption_version,created_at FROM encrypted_blob WHERE plaintext_sha256 = ? AND plaintext_size = ?",
        arrayOf(plaintextHash.bytes, plaintextSize),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.storedBlob() else null }
}

internal data class StoredBlob(
    val internalId: Long,
    val blobId: BlobId,
    val storageName: String,
    val plaintextHash: Hash256,
    val plaintextSize: Long,
    val mimeType: String,
    val extension: String?,
    val wrappedDataKey: ByteArray,
    val encryptionVersion: Int,
    val createdAt: Instant,
) {
    fun toAttachment(attachmentId: AttachmentId, displayName: String, importedAt: Instant): StoredAttachmentObject = StoredAttachmentObject(
        attachmentId,
        blobId,
        storageName,
        plaintextHash,
        plaintextSize,
        mimeType,
        extension,
        wrappedDataKey,
        encryptionVersion,
        displayName,
        importedAt,
    )
}

private fun Cursor.storedBlob(): StoredBlob = StoredBlob(
    internalId = getLong(0),
    blobId = BlobId(stableId(1)),
    storageName = getString(2),
    plaintextHash = hash256(3),
    plaintextSize = getLong(4),
    mimeType = getString(5),
    extension = nullableString(6),
    wrappedDataKey = getBlob(7),
    encryptionVersion = getInt(8),
    createdAt = Instant.ofEpochMilli(getLong(9)),
)

private fun Cursor.storedAttachment(): StoredAttachmentObject = StoredAttachmentObject(
    attachmentId = AttachmentId(stableId(0)),
    displayName = getString(1),
    importedAt = Instant.ofEpochMilli(getLong(2)),
    blobId = BlobId(stableId(3)),
    storageName = getString(4),
    plaintextHash = hash256(5),
    plaintextSize = getLong(6),
    mimeType = getString(7),
    extension = nullableString(8),
    wrappedDataKey = getBlob(9),
    encryptionVersion = getInt(10),
)

private fun SupportSQLiteDatabase.insertAttachment(
    internalId: Long,
    attachmentId: AttachmentId,
    blobInternalId: Long,
    displayName: String,
    importedAt: Instant,
) {
    execSQL(
        "INSERT INTO attachment(id,uid,blob_id,display_name,imported_at,status) VALUES(?,?,?,?,?,?)",
        arrayOf(
            internalId,
            attachmentId.value.bytes,
            blobInternalId,
            displayName,
            importedAt.toEpochMilli(),
            AttachmentStatus.ACTIVE.ordinal,
        ),
    )
}

private fun SupportSQLiteDatabase.allocateInternalId(table: String, uid: StableId): Long {
    check(long("SELECT COUNT(*) FROM $table WHERE uid = ?", arrayOf(uid.bytes)) == 0L) { "duplicate stable identifier" }
    val preferred = ByteBuffer.wrap(uid.bytes).getLong(StableId.BYTE_COUNT - Long.SIZE_BYTES) and Long.MAX_VALUE
    val normalized = if (preferred == 0L) 1L else preferred
    if (long("SELECT COUNT(*) FROM $table WHERE id = ?", arrayOf(normalized)) == 0L) return normalized
    return Math.addExact(long("SELECT COALESCE(MAX(id),0) FROM $table"), 1L).also { require(it > 0L) }
}

private fun SupportSQLiteDatabase.long(sql: String, args: Array<out Any?> = emptyArray()): Long = query(sql, args).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}

private fun Cursor.stableId(index: Int): StableId = StableId.fromBytes(getBlob(index)).successValue()

private fun Cursor.hash256(index: Int): Hash256 = Hash256.fromBytes(getBlob(index)).successValue()

private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun <T> DomainResult<T>.successValue(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> error("invalid persisted attachment value")
}
