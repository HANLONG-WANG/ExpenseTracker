@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package app.ledger.transfer.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.SecureLedgerFactPurgeAccess
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupObject
import app.ledger.transfer.domain.BackupObjectId
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupSnapshot
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotState
import java.time.Instant

data class BackupCatalogObject(val value: BackupObject, val storageName: String)

interface BackupCatalogPort {
    fun ensureRepository(repositoryId: BackupRepositoryId, kind: BackupRepositoryKind, handleId: StableId, createdAt: Instant)
    fun findObject(repositoryId: BackupRepositoryId, hash: Hash256, size: Long, kind: BackupObjectKind): BackupCatalogObject?
    fun recordObject(value: BackupObject): BackupCatalogObject
    fun publishSnapshot(snapshot: BackupSnapshot, manifestHash: Hash256, objectIds: List<BackupObjectId>)
    fun completeSnapshots(repositoryId: BackupRepositoryId): List<BackupSnapshot>
    fun deleteSnapshot(snapshotId: BackupSnapshotId): List<String>
    fun unreferencedObjects(repositoryId: BackupRepositoryId): List<String>
    fun deleteUnreferencedObject(repositoryId: BackupRepositoryId, storageName: String): Boolean
}

class SqlCipherBackupCatalog(
    private val bookId: StableId,
    private val access: SecurePrimaryLedgerAccess,
    private val factPurgeAccess: SecureLedgerFactPurgeAccess,
) : BackupCatalogPort {
    override fun ensureRepository(
        repositoryId: BackupRepositoryId,
        kind: BackupRepositoryKind,
        handleId: StableId,
        createdAt: Instant,
    ) = access.write(bookId) { database ->
        if (database.count("SELECT COUNT(*) FROM backup_repository WHERE uid=?", arrayOf(repositoryId.value.bytes)) == 0L) {
            database.execSQL(
                "INSERT INTO backup_repository(id,uid,kind,handle_uid,enabled,created_at,last_verified_at) VALUES(?,?,?,?,1,?,NULL)",
                arrayOf(database.nextId("backup_repository"), repositoryId.value.bytes, kind.ordinal, handleId.bytes, createdAt.toEpochMilli()),
            )
        }
    }

    override fun findObject(
        repositoryId: BackupRepositoryId,
        hash: Hash256,
        size: Long,
        kind: BackupObjectKind,
    ): BackupCatalogObject? = access.read(bookId) { database ->
        database.query(
            "SELECT o.uid,o.created_at FROM backup_object o JOIN backup_repository r ON r.id=o.repository_id " +
                "WHERE r.uid=? AND o.content_hash=? AND o.size_bytes=? AND o.kind=?",
            arrayOf(repositoryId.value.bytes, hash.bytes, size, kind.ordinal),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val id = BackupObjectId(cursor.stableId(0))
                BackupCatalogObject(BackupObject(id, repositoryId, hash, size, kind, Instant.ofEpochMilli(cursor.getLong(1))), id.storageName())
            }
        }
    }

    override fun recordObject(value: BackupObject): BackupCatalogObject = access.write(bookId) { database ->
        val existing = database.query(
            "SELECT uid,created_at,kind FROM backup_object WHERE repository_id=? AND content_hash=? AND size_bytes=?",
            arrayOf(database.requiredId("backup_repository", value.repositoryId.value), value.contentHash.bytes, value.size),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val id = BackupObjectId(cursor.stableId(0))
                val storedKind = BackupObjectKind.entries[cursor.getInt(2)]
                check(storedKind == value.kind) { "content hash collision across backup object kinds" }
                BackupCatalogObject(value.copy(id = id, createdAt = Instant.ofEpochMilli(cursor.getLong(1)), kind = storedKind), id.storageName())
            }
        }
        existing ?: run {
            database.execSQL(
                "INSERT INTO backup_object(id,uid,repository_id,content_hash,size_bytes,kind,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf(
                    database.nextId("backup_object"),
                    value.id.value.bytes,
                    database.requiredId("backup_repository", value.repositoryId.value),
                    value.contentHash.bytes,
                    value.size,
                    value.kind.ordinal,
                    value.createdAt.toEpochMilli(),
                ),
            )
            BackupCatalogObject(value, value.id.storageName())
        }
    }

    override fun publishSnapshot(snapshot: BackupSnapshot, manifestHash: Hash256, objectIds: List<BackupObjectId>) = access.write(bookId) { database ->
        require(snapshot.state == BackupSnapshotState.PREPARING)
        val existing = database.query(
            "SELECT r.uid,c.uid,s.local_revision,s.created_at,s.state,s.manifest_hash FROM backup_snapshot s " +
                "JOIN backup_repository r ON r.id=s.repository_id JOIN book_commit c ON c.id=s.head_commit_id WHERE s.uid=?",
            arrayOf(snapshot.id.value.bytes),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                false
            } else {
                check(cursor.stableId(0) == snapshot.repositoryId.value)
                check(cursor.stableId(1) == snapshot.headCommitId.value)
                check(cursor.getLong(2) == snapshot.localRevision.value)
                check(cursor.getLong(3) == snapshot.createdAt.toEpochMilli())
                check(cursor.getInt(4) == BackupSnapshotState.COMPLETE.ordinal)
                check(cursor.getBlob(5).contentEquals(manifestHash.bytes))
                true
            }
        }
        if (existing) {
            val linked = database.query(
                "SELECT o.uid FROM backup_snapshot_object l JOIN backup_object o ON o.id=l.object_id " +
                    "JOIN backup_snapshot s ON s.id=l.snapshot_id WHERE s.uid=? ORDER BY l.ordinal",
                arrayOf(snapshot.id.value.bytes),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(BackupObjectId(cursor.stableId(0))) } }
            check(linked == objectIds) { "published backup snapshot object set changed" }
            return@write
        }
        val snapshotInternal = database.nextId("backup_snapshot")
        database.execSQL(
            "INSERT INTO backup_snapshot(id,uid,repository_id,head_commit_id,local_revision,created_at,state,manifest_hash) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(
                snapshotInternal,
                snapshot.id.value.bytes,
                database.requiredId("backup_repository", snapshot.repositoryId.value),
                database.requiredId("book_commit", snapshot.headCommitId.value),
                snapshot.localRevision.value,
                snapshot.createdAt.toEpochMilli(),
                BackupSnapshotState.COMPLETE.ordinal,
                manifestHash.bytes,
            ),
        )
        objectIds.forEachIndexed { ordinal, objectId ->
            database.execSQL(
                "INSERT INTO backup_snapshot_object(snapshot_id,object_id,ordinal) VALUES(?,?,?)",
                arrayOf<Any>(snapshotInternal, database.requiredId("backup_object", objectId.value), ordinal.toLong()),
            )
        }
    }

    override fun completeSnapshots(repositoryId: BackupRepositoryId): List<BackupSnapshot> = access.read(bookId) { database ->
        database.query(
            "SELECT s.uid,c.uid,s.local_revision,s.created_at,s.manifest_hash FROM backup_snapshot s " +
                "JOIN backup_repository r ON r.id=s.repository_id JOIN book_commit c ON c.id=s.head_commit_id " +
                "WHERE r.uid=? AND s.state=? ORDER BY s.created_at DESC,s.id DESC",
            arrayOf(repositoryId.value.bytes, BackupSnapshotState.COMPLETE.ordinal),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.snapshot(repositoryId)) } }
    }

    override fun deleteSnapshot(snapshotId: BackupSnapshotId): List<String> = factPurgeAccess.write(bookId) { database ->
        val snapshotInternal = database.requiredId("backup_snapshot", snapshotId.value)
        val repositoryInternal = database.query(
            "SELECT repository_id FROM backup_snapshot WHERE id=?",
            arrayOf(snapshotInternal),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        database.execSQL("DELETE FROM backup_snapshot_object WHERE snapshot_id=?", arrayOf(snapshotInternal))
        database.execSQL("DELETE FROM backup_snapshot WHERE id=?", arrayOf(snapshotInternal))
        database.query(
            "SELECT o.uid FROM backup_object o LEFT JOIN backup_snapshot_object l ON l.object_id=o.id " +
                "WHERE o.repository_id=? AND l.object_id IS NULL",
            arrayOf(repositoryInternal),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(BackupObjectId(cursor.stableId(0)).storageName()) } }
    }

    override fun unreferencedObjects(repositoryId: BackupRepositoryId): List<String> = access.read(bookId) { database ->
        database.query(
            "SELECT o.uid FROM backup_object o JOIN backup_repository r ON r.id=o.repository_id " +
                "LEFT JOIN backup_snapshot_object l ON l.object_id=o.id WHERE r.uid=? AND l.object_id IS NULL",
            arrayOf(repositoryId.value.bytes),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(BackupObjectId(cursor.stableId(0)).storageName()) } }
    }

    override fun deleteUnreferencedObject(repositoryId: BackupRepositoryId, storageName: String): Boolean = factPurgeAccess.write(bookId) { database ->
        val objectId = storageName.removeSuffix(".object").hexStableId()
        database.compileStatement(
            "DELETE FROM backup_object WHERE uid=? AND repository_id=(SELECT id FROM backup_repository WHERE uid=?) " +
                "AND NOT EXISTS(SELECT 1 FROM backup_snapshot_object WHERE object_id=backup_object.id)",
        ).use { statement ->
            statement.bindBlob(1, objectId.bytes)
            statement.bindBlob(2, repositoryId.value.bytes)
            statement.executeUpdateDelete() == 1
        }
    }

    private fun Cursor.snapshot(repositoryId: BackupRepositoryId): BackupSnapshot {
        val snapshotId = BackupSnapshotId(stableId(0))
        val objectIds = access.read(bookId) { database ->
            database.query(
                "SELECT o.uid FROM backup_snapshot_object l JOIN backup_object o ON o.id=l.object_id " +
                    "JOIN backup_snapshot s ON s.id=l.snapshot_id WHERE s.uid=? ORDER BY l.ordinal",
                arrayOf(snapshotId.value.bytes),
            ).use { objects -> buildList { while (objects.moveToNext()) add(BackupObjectId(objects.stableId(0))) } }
        }
        return BackupSnapshot(
            snapshotId,
            repositoryId,
            BookCommitId(stableId(1)),
            LocalRevision.of(getLong(2)).required(),
            Instant.ofEpochMilli(getLong(3)),
            BackupSnapshotState.COMPLETE,
            Hash256.fromBytes(getBlob(4)).required(),
            objectIds,
        )
    }
}

private fun SupportSQLiteDatabase.count(sql: String, args: Array<out Any?> = emptyArray()): Long = query(sql, args).use {
    check(it.moveToFirst())
    it.getLong(0)
}

private fun SupportSQLiteDatabase.nextId(table: String): Long = Math.addExact(count("SELECT COALESCE(MAX(id),0) FROM $table"), 1L)

private fun SupportSQLiteDatabase.requiredId(table: String, uid: StableId): Long = query(
    "SELECT id FROM $table WHERE uid=?",
    arrayOf(uid.bytes),
).use { cursor ->
    check(cursor.moveToFirst()) { "$table stable id missing" }
    cursor.getLong(0)
}

private fun Cursor.stableId(index: Int): StableId = StableId.fromBytes(getBlob(index)).required()

private fun BackupObjectId.storageName(): String = value.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".object"

private fun String.hexStableId(): StableId {
    require(length == StableId.BYTE_COUNT * 2)
    return StableId.fromBytes(ByteArray(StableId.BYTE_COUNT) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }).required()
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: throw IllegalArgumentException(BackupFailure.CorruptManifest.code)
