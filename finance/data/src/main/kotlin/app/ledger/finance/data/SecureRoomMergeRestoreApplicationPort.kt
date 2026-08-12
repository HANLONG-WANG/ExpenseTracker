@file:Suppress("LongMethod", "MagicNumber", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecretBytes
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.DefaultLedgerWriteGate
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinanceMergeCommitVertex
import app.ledger.finance.application.FinanceMergeEntityVersion
import app.ledger.finance.application.FinanceMergeInspection
import app.ledger.finance.application.FinanceMergeTombstone
import app.ledger.finance.application.FinanceRestoreError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.application.MergeRestoreApplicationPort
import app.ledger.finance.application.PreparedRestoreLedger
import app.ledger.finance.application.RestoreIntegrityReport
import app.ledger.finance.domain.Book
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.BookId
import app.ledger.finance.domain.BookState
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.ContentHash
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MergeEntitySelection
import app.ledger.finance.domain.MergeRestoreCommand
import app.ledger.finance.domain.PlanningOperationContext
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.RuleSetVersion
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Same-book merge inspection and shadow application; the only mutation entry is the shared coordinator. */
class SecureRoomMergeRestoreApplicationPort(
    context: Context,
    private val keyHierarchy: DeviceKeyHierarchy,
    private val stableIds: StableIdSource,
    private val now: () -> Instant,
    private val restoreExchange: SecureRoomRestoreLedgerApplicationPort,
    private val writeGate: LedgerWriteGate = DefaultLedgerWriteGate(),
) : MergeRestoreApplicationPort {
    private val applicationContext = context.applicationContext
    private val sessions = ConcurrentHashMap<StableId, MergeSourceSession>()

    override suspend fun inspect(value: MaterializedRestorePackage): DomainResult<FinanceMergeInspection> = protect {
        val keySource = File(value.portableKeyMaterialPath).canonicalFile
        val databaseSource = File(value.databasePath).canonicalFile
        require(keySource.isFile && databaseSource.isFile)
        val portable = SecretBytes.copyOf(keySource.readMergeKeyMaterial())
        val replacement = try {
            keyHierarchy.preparePortableReplacement(value.bookId, portable)
        } finally {
            portable.close()
        }
        try {
            val sourceName = mergeSourceName(value.operationId)
            applicationContext.deleteDatabase(sourceName)
            copyDurably(databaseSource, applicationContext.getDatabasePath(sourceName))
            val incoming = replacement.restoredDatabaseDek.useBytes { key ->
                EncryptedDatabaseFactory.openLedgerCopy(applicationContext, sourceName, key)
            }
            val inspection = try {
                withLive(value.bookId) { live -> inspectDatabases(live.openHelper.readableDatabase, incoming.openHelper.readableDatabase) }
            } finally {
                incoming.close()
            }
            if (inspection.bookId != value.bookId) abort(FinanceRestoreError.BookMismatch)
            sessions.remove(value.operationId)?.close()
            sessions[value.operationId] = MergeSourceSession(
                value.copy(databasePath = applicationContext.getDatabasePath(sourceName).absolutePath),
                replacement,
                inspection,
            )
            DomainResult.Success(inspection)
        } catch (error: Exception) {
            replacement.close()
            throw error
        }
    }

    override suspend fun prepareResolved(
        operationId: StableId,
        commonAncestor: BookCommitId,
        incomingHead: BookCommitId,
        selections: List<MergeEntitySelection>,
    ): DomainResult<PreparedRestoreLedger> = protect {
        val session = sessions[operationId] ?: abort(FinanceRestoreError.RecoveryRequired)
        if (session.inspection.incomingHead != incomingHead || commonAncestor !in session.inspection.graph.map { it.id }) {
            abort(FinanceRestoreError.IntegrityFailed)
        }
        val shadowName = shadowName(operationId)
        applicationContext.deleteDatabase(shadowName)
        checkpointLive(session.inspection.bookId)
        copyDurably(
            applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME),
            applicationContext.getDatabasePath(shadowName),
        )
        val incoming = session.keys.restoredDatabaseDek.useBytes { key ->
            EncryptedDatabaseFactory.openLedgerCopy(applicationContext, mergeSourceName(operationId), key)
        }
        val shadow = keyHierarchy.open(session.inspection.bookId).use { keys ->
            keys.databaseDek.useBytes { key -> EncryptedDatabaseFactory.openLedgerCopy(applicationContext, shadowName, key) }
        }
        try {
            val operation = PlanningOperationContext(
                BookCommitId(stableIds.nextStableId()),
                now(),
                DeviceInstanceId(stableIds.nextStableId()),
            )
            val draft = MergeRestoreCommand(
                CommandId(stableIds.nextStableId()),
                Hash256.sha256(ByteArray(0)),
                operationId,
                commonAncestor,
                incomingHead,
                selections,
            )
            val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
            val revisionBeforeMerge = shadow.readLedger { connection ->
                RoomMergeImporter(incoming.openHelper.readableDatabase, command).revisionBeforeMerge(connection)
            }
            val snapshot = shadow.readLedger { connection -> planningSnapshot(connection, operation, revisionBeforeMerge) }
            val repository = RoomFinancialCommitRepository(
                shadow,
                beforeCommitSideEffect = FinancialCommitSideEffect { target, plan ->
                    RoomMergeImporter(incoming.openHelper.readableDatabase, command).apply(
                        target,
                        plan.targetLocalRevision.value,
                    )
                },
                forceFullProjectionRebuild = true,
            )
            val result = DefaultFinancialMutationCoordinator(
                writeGate,
                repository,
                object : FinancialPlanningSnapshotRepository {
                    override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
                },
                FinancialPlanningPort(DeterministicFinancialPlanner::plan),
                repository,
            ).execute(command)
            val receipt = when (result) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> abort(result.error)
            }
            shadow.readLedger { it.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            val report = shadow.readLedger { validateMerged(it, session.inspection.bookId, receipt.commitId) }
            if (!report.projectionsValid) abort(FinanceRestoreError.ProjectionFailed(report.projectionFailureCodes))
            if (!report.isValid) abort(FinanceRestoreError.IntegrityFailed)
            restoreExchange.registerPreparedMerge(operationId, session.inspection.bookId, receipt.commitId, session.inspection.localHead)
            DomainResult.Success(PreparedRestoreLedger(operationId, receipt.commitId, session.inspection.localHead, report))
        } finally {
            incoming.close()
            shadow.close()
        }
    }

    override fun cleanup(operationId: StableId) {
        sessions.remove(operationId)?.close()
        applicationContext.deleteDatabase(mergeSourceName(operationId))
    }

    private fun inspectDatabases(local: SupportSQLiteDatabase, incoming: SupportSQLiteDatabase): FinanceMergeInspection {
        val localIdentity = local.identity()
        val incomingIdentity = incoming.identity()
        if (localIdentity.bookId != incomingIdentity.bookId) abort(FinanceRestoreError.BookMismatch)
        if (localIdentity.currency != incomingIdentity.currency) abort(FinanceRestoreError.BaseCurrencyMismatch)
        val graph = (local.graph() + incoming.graph()).associateBy(FinanceMergeCommitVertex::id).values.toList()
        val ancestor = commonAncestor(graph, localIdentity.head, incomingIdentity.head)
            ?: abort(FinanceRestoreError.IntegrityFailed)
        val ancestorDatabase = if (local.hasCommit(ancestor)) local else incoming
        return FinanceMergeInspection(
            localIdentity.bookId,
            localIdentity.currency,
            localIdentity.head,
            incomingIdentity.head,
            graph,
            ancestorDatabase.versionsAt(ancestor),
            local.versionsAt(localIdentity.head),
            incoming.versionsAt(incomingIdentity.head),
            local.tombstones(),
            incoming.tombstones(),
        )
    }

    private fun SupportSQLiteDatabase.identity(): Identity = query(
        "SELECT b.uid,b.base_currency,c.uid FROM book b JOIN book_commit c ON c.id=b.head_commit_id WHERE b.id=1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) abort(FinanceDataError.CorruptData)
        Identity(cursor.stableIdAt(0), cursor.getString(1), BookCommitId(cursor.stableIdAt(2)))
    }

    private fun SupportSQLiteDatabase.graph(): List<FinanceMergeCommitVertex> = query(
        "SELECT c.uid,p.uid FROM book_commit c LEFT JOIN book_commit_parent x ON x.commit_id=c.id " +
            "LEFT JOIN book_commit p ON p.id=x.parent_commit_id ORDER BY c.id,x.ordinal",
    ).use { cursor ->
        val values = linkedMapOf<BookCommitId, MutableList<BookCommitId>>()
        while (cursor.moveToNext()) {
            val commit = BookCommitId(cursor.stableIdAt(0))
            val parents = values.getOrPut(commit) { mutableListOf() }
            if (!cursor.isNull(1)) parents += BookCommitId(cursor.stableIdAt(1))
        }
        values.map { (id, parents) -> FinanceMergeCommitVertex(id, parents) }
    }

    private fun SupportSQLiteDatabase.versionsAt(head: BookCommitId): Map<Pair<EntityType, StableId>, FinanceMergeEntityVersion> = query(
        "WITH RECURSIVE a(id,depth) AS (SELECT id,0 FROM book_commit WHERE uid=? UNION ALL " +
            "SELECT p.parent_commit_id,a.depth+1 FROM book_commit_parent p JOIN a ON a.id=p.commit_id), " +
            "ranked AS (SELECT ec.entity_type,ec.entity_uid,ec.after_hash,c.uid,a.depth,c.local_revision," +
            "ROW_NUMBER() OVER(PARTITION BY ec.entity_type,ec.entity_uid ORDER BY a.depth,c.local_revision DESC) rank " +
            "FROM a JOIN book_commit c ON c.id=a.id JOIN entity_change ec ON ec.commit_id=c.id) " +
            "SELECT entity_type,entity_uid,after_hash,uid,local_revision FROM ranked WHERE rank=1 AND after_hash IS NOT NULL",
        arrayOf(head.value.bytes),
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val type = EntityType.entries.getOrNull(cursor.getInt(0)) ?: abort(FinanceDataError.CorruptData)
                val entity = cursor.stableIdAt(1)
                put(
                    type to entity,
                    FinanceMergeEntityVersion(
                        type,
                        entity,
                        ContentHash(Hash256.fromBytes(cursor.getBlob(2)).required()),
                        BookCommitId(cursor.stableIdAt(3)),
                        cursor.getLong(4),
                    ),
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.tombstones(): Map<Pair<EntityType, StableId>, FinanceMergeTombstone> = query(
        "SELECT t.entity_type,t.entity_uid,c.uid,t.purged_at,t.purge_generation FROM purge_tombstone t " +
            "JOIN book_commit c ON c.id=t.purge_commit_id",
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val type = EntityType.entries.getOrNull(cursor.getInt(0)) ?: abort(FinanceDataError.CorruptData)
                val id = cursor.stableIdAt(1)
                put(type to id, FinanceMergeTombstone(type, id, BookCommitId(cursor.stableIdAt(2)), cursor.getLong(3), cursor.getLong(4)))
            }
        }
    }

    private fun planningSnapshot(
        database: SupportSQLiteDatabase,
        operation: PlanningOperationContext,
        revisionBeforeMerge: Long,
    ): PlanningSnapshot {
        val row = database.query(
            "SELECT b.uid,b.base_currency,b.default_zone_id,c.uid,b.local_revision,b.valuation_revision,b.rule_set_version," +
                "b.created_at,b.first_financial_commit_at,b.state FROM book b JOIN book_commit c ON c.id=b.head_commit_id WHERE b.id=1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) abort(FinanceDataError.CorruptData)
            Book(
                BookId(cursor.stableIdAt(0)),
                CurrencyCode.parse(cursor.getString(1)).required(),
                ZoneId.of(cursor.getString(2)),
                BookCommitId(cursor.stableIdAt(3)),
                LocalRevision.of(revisionBeforeMerge).required(),
                LocalRevision.of(cursor.getLong(5)).required(),
                RuleSetVersion.of(cursor.getInt(6)).required(),
                Instant.ofEpochMilli(cursor.getLong(7)),
                cursor.getLong(8).takeUnless { cursor.isNull(8) }?.let(Instant::ofEpochMilli),
                BookState.entries[cursor.getInt(9)],
            )
        }
        return PlanningSnapshot(row, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(), operationContext = operation)
    }

    private fun validateMerged(database: SupportSQLiteDatabase, bookId: StableId, head: BookCommitId): RestoreIntegrityReport {
        val audit = DatabaseIntegrityAudit.run(database)
        val identity = database.identity()
        val mismatches = database.query("SELECT COUNT(*) FROM projection_revision_audit WHERE min_revision<>book_revision OR max_revision<>book_revision")
            .use { if (it.moveToFirst()) it.getLong(0) else 1L }
        return RestoreIntegrityReport(
            true,
            true,
            audit.capability.sqlCipherVersion.isNotBlank(),
            true,
            audit.foreignKeyViolationCount == 0,
            audit.unbalancedJournalCount == 0,
            mismatches == 0L,
            audit.invalidCurrentSubtypeCount == 0,
            true,
            identity.bookId == bookId && identity.head == head,
            identity.currency.matches(Regex("[A-Z]{3}")),
            if (mismatches == 0L) emptySet() else setOf("PROJECTION_REVISION_AUDIT"),
        )
    }

    private fun commonAncestor(
        graph: List<FinanceMergeCommitVertex>,
        local: BookCommitId,
        incoming: BookCommitId,
    ): BookCommitId? {
        val parents = graph.associate { it.id to it.parents }
        fun distances(head: BookCommitId): Map<BookCommitId, Int> {
            val result = mutableMapOf<BookCommitId, Int>()
            val queue = ArrayDeque<Pair<BookCommitId, Int>>().apply { add(head to 0) }
            while (queue.isNotEmpty()) {
                val (id, distance) = queue.removeFirst()
                if ((result[id] ?: Int.MAX_VALUE) <= distance) continue
                result[id] = distance
                parents[id].orEmpty().forEach { queue += it to Math.addExact(distance, 1) }
            }
            return result
        }
        val left = distances(local)
        val right = distances(incoming)
        return left.keys.intersect(right.keys).minWithOrNull(compareBy<BookCommitId> { left.getValue(it) + right.getValue(it) }.thenBy { it.value.toString() })
    }

    private fun SupportSQLiteDatabase.hasCommit(id: BookCommitId): Boolean = query(
        "SELECT COUNT(*) FROM book_commit WHERE uid=?",
        arrayOf(id.value.bytes),
    ).use { it.moveToFirst() && it.getLong(0) == 1L }

    private fun checkpointLive(bookId: StableId) = withLive(bookId) { database ->
        database.readLedger { it.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
    }

    private fun <T> withLive(bookId: StableId, block: (LedgerDatabase) -> T): T = keyHierarchy.open(bookId).use { keys ->
        keys.databaseDek.useBytes { key ->
            val database = EncryptedDatabaseFactory.openPrimary(applicationContext, key)
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }

    private fun copyDurably(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.fd.sync()
            }
        }
    }

    private inline fun <T> protect(block: () -> DomainResult<T>): DomainResult<T> = try {
        block()
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceRestoreError.IntegrityFailed)
    }

    private data class Identity(val bookId: StableId, val currency: String, val head: BookCommitId)
    private data class MergeSourceSession(
        val packageValue: MaterializedRestorePackage,
        val keys: app.ledger.core.security.PreparedDeviceLedgerKeyReplacement,
        val inspection: FinanceMergeInspection,
    ) : AutoCloseable {
        override fun close() = keys.close()
    }

    private fun shadowName(operationId: StableId) = "ledger_shadow_${operationId.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}.db"
    private fun mergeSourceName(operationId: StableId) = "ledger_safety_${operationId.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}.db"

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

private fun File.readMergeKeyMaterial(): ByteArray {
    require(length() in 1..(256 * 1024).toLong())
    return FileInputStream(this).use { input -> ByteArray(length().toInt()).also { input.readFully(it) } }
}

private fun FileInputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = read(target, offset, target.size - offset)
        if (count < 0) error("short key material")
        if (count > 0) offset += count
    }
    require(read() == -1)
}

private fun android.database.Cursor.stableIdAt(index: Int): StableId = StableId.fromBytes(getBlob(index)).required()
private fun <T> DomainResult<T>.required(): T = (this as DomainResult.Success).value
