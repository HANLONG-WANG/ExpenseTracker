@file:Suppress("TooManyFunctions")

package app.ledger.transfer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.StableEntityReference
import app.ledger.transfer.domain.MergeConflict
import app.ledger.transfer.domain.MergeConflictKind
import app.ledger.transfer.domain.MergeDecision
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.MergeSessionState
import app.ledger.transfer.domain.RestoreFailure
import app.ledger.transfer.domain.ThreeWayMergePlan
import java.security.MessageDigest
import java.time.Instant

/** Durable SQLCipher conflict ledger. It stores only stable ids/hashes/generation, never entity text or amounts. */
class SqlCipherMergeSessionStore(
    private val access: SecurePrimaryLedgerAccess,
    private val now: () -> Instant,
) : MergeSessionStore {
    override fun save(
        bookId: StableId,
        operationId: StableId,
        plan: ThreeWayMergePlan,
    ): DomainResult<Unit> = protect {
        access.write(bookId) { database ->
            val operation = database.internalId("background_operation", operationId)
            database.execSQL("DELETE FROM merge_session WHERE operation_id=?", arrayOf(operation))
            val sessionUid = plan.conflicts.firstOrNull()?.sessionId?.value ?: derivedId(operationId, "merge-session")
            val sessionId = database.nextId("merge_session")
            database.execSQL(
                "INSERT INTO merge_session(id,uid,operation_id,common_ancestor_commit_id,local_head_commit_id," +
                    "incoming_head_commit_uid,state) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    sessionId,
                    sessionUid.bytes,
                    operation,
                    database.internalId("book_commit", plan.commonAncestor.value),
                    database.internalId("book_commit", plan.localHead.value),
                    plan.incomingHead.value.bytes,
                    if (plan.conflicts.isEmpty()) MergeSessionState.APPLYING.ordinal else MergeSessionState.AWAITING_RESOLUTION.ordinal,
                ),
            )
            plan.conflicts.forEach { database.insertConflict(sessionId, it) }
            plan.automaticDecisions.filterIsInstance<MergeDecision.KeepPurgeTombstone>().forEach { decision ->
                val id = derivedId(operationId, "purge:${decision.entity.type.name}:${decision.entity.stableId}")
                database.insertConflict(
                    sessionId,
                    MergeConflict(
                        app.ledger.transfer.domain.MergeConflictId(id),
                        app.ledger.transfer.domain.MergeSessionId(sessionUid),
                        MergeConflictKind.PURGED_ENTITY,
                        null,
                        null,
                        null,
                        decision.tombstone,
                        MergeResolution.KeepPurgeTombstone,
                    ),
                )
                val conflict = database.internalId("merge_conflict", id)
                database.execSQL(
                    "INSERT INTO merge_resolution(conflict_id,resolution,resolved_at,resulting_commit_id) VALUES(?,?,?,NULL)",
                    arrayOf<Any>(conflict, MergeResolution.KeepPurgeTombstone.ordinal(), now().toEpochMilli()),
                )
            }
        }
        DomainResult.Success(Unit)
    }

    override fun resolve(
        bookId: StableId,
        conflictId: StableId,
        resolution: MergeResolution,
    ): DomainResult<Unit> = protect {
        access.write(bookId) { database ->
            val conflict = database.internalId("merge_conflict", conflictId)
            val purgeGeneration = database.query(
                "SELECT purge_generation FROM merge_conflict WHERE id=?",
                arrayOf(conflict),
            ).use { cursor ->
                if (!cursor.moveToFirst()) error("missing conflict")
                if (cursor.isNull(0)) null else cursor.getLong(0)
            }
            require(purgeGeneration == null || resolution == MergeResolution.KeepPurgeTombstone)
            database.execSQL("UPDATE merge_conflict SET resolution=? WHERE id=?", arrayOf<Any>(resolution.ordinal(), conflict))
            database.execSQL(
                "INSERT INTO merge_resolution(conflict_id,resolution,resolved_at,resulting_commit_id) VALUES(?,?,?,NULL) " +
                    "ON CONFLICT(conflict_id) DO UPDATE SET resolution=excluded.resolution,resolved_at=excluded.resolved_at",
                arrayOf<Any>(conflict, resolution.ordinal(), now().toEpochMilli()),
            )
        }
        DomainResult.Success(Unit)
    }

    override fun markApplying(bookId: StableId, operationId: StableId): DomainResult<Unit> = state(
        bookId,
        operationId,
        MergeSessionState.APPLYING,
        null,
    )

    override fun markComplete(
        bookId: StableId,
        operationId: StableId,
        resultingCommit: BookCommitId,
    ): DomainResult<Unit> = state(bookId, operationId, MergeSessionState.COMPLETE, resultingCommit)

    private fun state(
        bookId: StableId,
        operationId: StableId,
        state: MergeSessionState,
        resultingCommit: BookCommitId?,
    ): DomainResult<Unit> = protect {
        access.write(bookId) { database ->
            val operation = database.internalId("background_operation", operationId)
            val changed = database.compileStatement("UPDATE merge_session SET state=? WHERE operation_id=?").apply {
                bindLong(1, state.ordinal.toLong())
                bindLong(2, operation)
            }.executeUpdateDelete()
            require(changed == 1)
            resultingCommit?.let { commit ->
                val commitId = database.internalId("book_commit", commit.value)
                database.execSQL(
                    "UPDATE merge_resolution SET resulting_commit_id=? WHERE conflict_id IN " +
                        "(SELECT c.id FROM merge_conflict c JOIN merge_session s ON s.id=c.session_id WHERE s.operation_id=?)",
                    arrayOf(commitId, operation),
                )
            }
        }
        DomainResult.Success(Unit)
    }

    private fun SupportSQLiteDatabase.insertConflict(sessionId: Long, conflict: MergeConflict) {
        val entity = conflict.entity()
        execSQL(
            "INSERT INTO merge_conflict(id,uid,session_id,kind,entity_type,entity_uid,ancestor_hash,local_hash," +
                "incoming_hash,purge_generation,resolution) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                nextId("merge_conflict"),
                conflict.id.value.bytes,
                sessionId,
                conflict.kind.ordinal,
                entity.type.ordinal,
                entity.stableId.bytes,
                conflict.ancestor?.contentHash?.value?.bytes,
                conflict.local?.contentHash?.value?.bytes,
                conflict.incoming?.contentHash?.value?.bytes,
                conflict.purgeTombstone?.purgeGeneration,
                conflict.resolution?.ordinal(),
            ),
        )
    }

    private fun MergeConflict.entity(): StableEntityReference = local?.let { StableEntityReference(it.entityType, it.entityId) }
        ?: incoming?.let { StableEntityReference(it.entityType, it.entityId) }
        ?: ancestor?.let { StableEntityReference(it.entityType, it.entityId) }
        ?: requireNotNull(purgeTombstone).entity

    private fun MergeResolution.ordinal(): Int = when (this) {
        MergeResolution.KeepLocal -> 0
        MergeResolution.KeepIncoming -> 1
        MergeResolution.KeepPurgeTombstone -> 2
    }

    private fun SupportSQLiteDatabase.internalId(table: String, id: StableId): Long = query(
        "SELECT id FROM $table WHERE uid=?",
        arrayOf(id.bytes),
    ).use { if (it.moveToFirst()) it.getLong(0) else error("missing $table") }

    private fun SupportSQLiteDatabase.nextId(table: String): Long = query("SELECT COALESCE(MAX(id),0)+1 FROM $table").use {
        if (it.moveToFirst()) it.getLong(0) else error("id allocation failed")
    }

    private inline fun protect(block: () -> DomainResult<Unit>): DomainResult<Unit> = try {
        block()
    } catch (_: Exception) {
        DomainResult.Failure(RestoreFailure.IntegrityFailed)
    }

    private fun derivedId(source: StableId, label: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256").digest(source.bytes + label.toByteArray(Charsets.UTF_8)).copyOf(StableId.BYTE_COUNT),
    ).let { (it as DomainResult.Success).value }
}
