@file:Suppress("LongMethod", "NestedBlockDepth", "ReturnCount", "TooManyFunctions")

package app.ledger.finance.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.MergeEntitySource
import app.ledger.finance.domain.MergeRestoreCommand

/** Bounded row copier used only inside the coordinator-owned merge transaction on a shadow ledger. */
internal class RoomMergeImporter(
    private val incoming: SupportSQLiteDatabase,
    private val command: MergeRestoreCommand,
) {
    private val targetIds = mutableMapOf<Pair<String, Long>, Long>()
    private val copying = mutableSetOf<Pair<String, Long>>()
    private lateinit var target: SupportSQLiteDatabase
    private val tableContracts by lazy { incoming.tableContracts() }

    fun revisionBeforeMerge(target: SupportSQLiteDatabase): Long {
        val bookRevision = target.long("SELECT local_revision FROM book WHERE id=1")
        val maximumCommitRevision = target.long("SELECT COALESCE(MAX(local_revision),0) FROM book_commit")
        if (bookRevision != maximumCommitRevision) abort(FinanceDataError.CorruptData)
        return Math.addExact(bookRevision, missingCommitIds(target).size.toLong())
    }

    fun apply(target: SupportSQLiteDatabase, expectedMergeRevision: Long) {
        this.target = target
        target.execSQL("PRAGMA defer_foreign_keys=ON")
        importCommitGraph(expectedMergeRevision)
        command.selections.filter { it.source == MergeEntitySource.INCOMING }.forEach { selection ->
            val table = ENTITY_TABLES[selection.entity.type] ?: abort(FinanceDataError.CorruptData)
            val incomingId = incoming.idForUid(table, selection.entity.stableId.bytes)
                ?: abort(FinanceDataError.CorruptData)
            copyById(table, incomingId, replaceExisting = true)
            copyEntityRevisionAudit(selection.entity.type, selection.entity.stableId.bytes)
            if (selection.entity.type == EntityType.TRANSACTION) copyForward("business_transaction", incomingId)
        }
        importEntityChangesForImportedCommits()
        command.selections.filter { it.source == MergeEntitySource.PURGE_TOMBSTONE }.forEach { selection ->
            importWinningTombstone(selection.entity.type, selection.entity.stableId.bytes)
        }
    }

    private fun missingCommitIds(target: SupportSQLiteDatabase): List<Long> {
        val reachable = incoming.query(
            "WITH RECURSIVE reachable(id) AS (" +
                "SELECT id FROM book_commit WHERE uid=? UNION SELECT p.parent_commit_id FROM book_commit_parent p " +
                "JOIN reachable r ON r.id=p.commit_id) SELECT id FROM reachable",
            arrayOf(command.incomingHeadCommitId.value.bytes),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        return reachable.filter { sourceId ->
            val uid = incoming.blob("SELECT uid FROM book_commit WHERE id=?", sourceId)
            target.idForUid("book_commit", uid) == null
        }.sortedBy { incoming.long("SELECT local_revision FROM book_commit WHERE id=?", it) }
    }

    private fun importCommitGraph(expectedMergeRevision: Long) {
        val reachable = incoming.query(
            "WITH RECURSIVE reachable(id) AS (" +
                "SELECT id FROM book_commit WHERE uid=? UNION SELECT p.parent_commit_id FROM book_commit_parent p " +
                "JOIN reachable r ON r.id=p.commit_id) SELECT id FROM reachable",
            arrayOf(command.incomingHeadCommitId.value.bytes),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        val missing = missingCommitIds(target)
        val reservedStart = Math.addExact(target.long("SELECT local_revision FROM book WHERE id=1"), 1L)
        if (Math.addExact(reservedStart, missing.size.toLong()) != expectedMergeRevision) {
            abort(FinanceDataError.CorruptData)
        }
        missing.forEachIndexed { index, sourceId ->
            val row = incoming.row("book_commit", sourceId)
            val targetId = target.nextId("book_commit")
            targetIds["book_commit" to sourceId] = targetId
            target.execSQL(
                "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) " +
                    "VALUES(?,?,?,?,NULL,?,?,?)",
                arrayOf(
                    targetId,
                    row.value("uid"),
                    Math.addExact(reservedStart, index.toLong()),
                    row.value("kind"),
                    row.value("device_instance_uid"),
                    row.value("created_at"),
                    row.value("root_hash"),
                ),
            )
        }
        reachable.forEach { sourceId ->
            if (("book_commit" to sourceId) !in targetIds) {
                val uid = incoming.blob("SELECT uid FROM book_commit WHERE id=?", sourceId)
                targetIds["book_commit" to sourceId] = target.idForUid("book_commit", uid)
                    ?: abort(FinanceDataError.CorruptData)
            }
        }
        missing.forEach { sourceId ->
            incoming.query(
                "SELECT parent_commit_id,ordinal FROM book_commit_parent WHERE commit_id=? ORDER BY ordinal",
                arrayOf(sourceId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    target.execSQL(
                        "INSERT INTO book_commit_parent(commit_id,parent_commit_id,ordinal) VALUES(?,?,?)",
                        arrayOf<Any>(
                            mapped("book_commit", sourceId),
                            mapped("book_commit", cursor.getLong(0)),
                            cursor.getInt(1),
                        ),
                    )
                }
            }
        }
    }

    private fun copyById(table: String, sourceId: Long, replaceExisting: Boolean = false): Long {
        val key = table to sourceId
        targetIds[key]?.let { return it }
        val contract = tableContracts[table] ?: abort(FinanceDataError.CorruptData)
        require(contract.idColumn == "id")
        val row = incoming.row(table, sourceId)
        val uid = contract.uidColumn?.let(row::value) as? ByteArray
        val existing = uid?.let { target.idForUid(table, it) }
        val targetId = existing ?: target.nextId(table)
        targetIds[key] = targetId
        if (!copying.add(key)) return targetId
        try {
            val transformed = row.values.toMutableMap()
            transformed["id"] = targetId
            contract.foreignKeys.forEach { foreignKey ->
                val sourceReference = transformed[foreignKey.from] as? Number ?: return@forEach
                transformed[foreignKey.from] = copyById(foreignKey.table, sourceReference.toLong())
            }
            if (existing != null) {
                if (replaceExisting) target.updateRow(table, transformed, contract.columns.filterNot { it == "id" || it == "uid" })
            } else {
                target.insertRow(table, transformed, contract.columns)
            }
            return targetId
        } finally {
            copying -= key
        }
    }

    private fun copyForward(parentTable: String, parentSourceId: Long) {
        tableContracts.values.filter { child -> child.name in FORWARD_FACT_TABLES }.forEach { child ->
            child.foreignKeys.filter { it.table == parentTable && it.to == "id" }.forEach { foreignKey ->
                incoming.query(
                    "SELECT ${child.idColumn ?: "rowid"} FROM ${child.name} WHERE ${foreignKey.from}=?",
                    arrayOf(parentSourceId),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (child.idColumn == "id") {
                            val sourceChildId = cursor.getLong(0)
                            val alreadyPresent = child.uidColumn?.let { uidColumn ->
                                val uid = incoming.row(child.name, sourceChildId).value(uidColumn) as? ByteArray
                                uid != null && target.idForUid(child.name, uid) != null
                            } == true
                            copyById(child.name, sourceChildId)
                            if (!alreadyPresent) copyForward(child.name, sourceChildId)
                        } else {
                            copyCompositeRows(child, foreignKey.from, parentSourceId)
                        }
                    }
                }
            }
        }
    }

    private fun copyCompositeRows(contract: TableContract, childColumn: String, parentSourceId: Long) {
        incoming.query("SELECT * FROM ${contract.name} WHERE $childColumn=?", arrayOf(parentSourceId)).use { cursor ->
            while (cursor.moveToNext()) {
                val values = cursor.values().toMutableMap()
                contract.foreignKeys.forEach { foreignKey ->
                    val sourceReference = values[foreignKey.from] as? Number ?: return@forEach
                    values[foreignKey.from] = copyById(foreignKey.table, sourceReference.toLong())
                }
                target.insertOrIgnoreRow(contract.name, values, contract.columns)
            }
        }
    }

    private fun copyEntityRevisionAudit(type: EntityType, uid: ByteArray) {
        incoming.query(
            "SELECT id FROM entity_revision WHERE entity_type=? AND entity_uid=? ORDER BY revision_no",
            arrayOf(type.ordinal, uid),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getLong(0)
                val row = incoming.row("entity_revision", sourceId)
                val revisionUid = row.value("uid") as? ByteArray ?: abort(FinanceDataError.CorruptData)
                if (target.idForUid("entity_revision", revisionUid) != null) continue
                val values = row.values.toMutableMap()
                val targetId = target.nextId("entity_revision")
                values["id"] = targetId
                values["commit_id"] = mapped("book_commit", (row.value("commit_id") as Number).toLong())
                values["revision_no"] = Math.addExact(
                    target.long(
                        "SELECT COALESCE(MAX(revision_no),0) FROM entity_revision WHERE entity_type=? AND entity_uid=?",
                        arrayOf(type.ordinal, uid),
                    ),
                    1L,
                )
                target.insertRow("entity_revision", values, tableContracts.getValue("entity_revision").columns)
                targetIds["entity_revision" to sourceId] = targetId
            }
        }
    }

    private fun importEntityChangesForImportedCommits() {
        targetIds.filterKeys { it.first == "book_commit" }.forEach { (key, commitId) ->
            val sourceId = key.second
            if (target.long("SELECT COUNT(*) FROM entity_change WHERE commit_id=?", commitId) != 0L) return@forEach
            incoming.query("SELECT * FROM entity_change WHERE commit_id=?", arrayOf(sourceId)).use { cursor ->
                while (cursor.moveToNext()) {
                    val values = cursor.values().toMutableMap()
                    values["commit_id"] = commitId
                    target.insertOrIgnoreRow("entity_change", values, values.keys.toList())
                }
            }
        }
    }

    private fun importWinningTombstone(type: EntityType, uid: ByteArray) {
        val tombstone = incoming.query(
            "SELECT purge_commit_id,purged_at,purge_generation FROM purge_tombstone WHERE entity_type=? AND entity_uid=?",
            arrayOf(type.ordinal, uid),
        ).use { cursor ->
            if (!cursor.moveToFirst()) abort(FinanceDataError.CorruptData)
            Triple(mapped("book_commit", cursor.getLong(0)), cursor.getLong(1), cursor.getLong(2))
        }
        target.execSQL(
            "INSERT INTO purge_tombstone(entity_type,entity_uid,purge_commit_id,purged_at,purge_generation) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(entity_type,entity_uid) DO UPDATE SET purge_commit_id=excluded.purge_commit_id," +
                "purged_at=excluded.purged_at,purge_generation=excluded.purge_generation " +
                "WHERE excluded.purge_generation>purge_tombstone.purge_generation",
            arrayOf(type.ordinal, uid, tombstone.first, tombstone.second, tombstone.third),
        )
    }

    private fun mapped(table: String, sourceId: Long): Long = targetIds[table to sourceId]
        ?: abort(FinanceDataError.CorruptData)

    private data class ForeignKey(val from: String, val table: String, val to: String)
    private data class TableContract(
        val name: String,
        val columns: List<String>,
        val idColumn: String?,
        val uidColumn: String?,
        val foreignKeys: List<ForeignKey>,
    )

    private data class Row(val values: Map<String, Any?>) {
        fun value(name: String): Any? = values[name]
    }

    private fun SupportSQLiteDatabase.tableContracts(): Map<String, TableContract> = query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND substr(name,1,1)<>'_'",
    ).use { tables ->
        buildMap {
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                if (!SAFE_IDENTIFIER.matches(table)) abort(FinanceDataError.CorruptData)
                val columns = query("PRAGMA table_info($table)").use { info ->
                    buildList { while (info.moveToNext()) add(info.getString(info.getColumnIndexOrThrow("name"))) }
                }
                val foreignKeys = query("PRAGMA foreign_key_list($table)").use { keys ->
                    buildList {
                        while (keys.moveToNext()) {
                            add(
                                ForeignKey(
                                    keys.getString(keys.getColumnIndexOrThrow("from")),
                                    keys.getString(keys.getColumnIndexOrThrow("table")),
                                    keys.getString(keys.getColumnIndexOrThrow("to")),
                                ),
                            )
                        }
                    }
                }
                put(table, TableContract(table, columns, "id".takeIf(columns::contains), "uid".takeIf(columns::contains), foreignKeys))
            }
        }
    }

    private fun SupportSQLiteDatabase.row(table: String, id: Long): Row = query(
        "SELECT * FROM $table WHERE id=?",
        arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) Row(cursor.values()) else abort(FinanceDataError.CorruptData) }

    private fun Cursor.values(): Map<String, Any?> = buildMap {
        columnNames.forEachIndexed { index, name ->
            put(
                name,
                when (getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> getString(index)
                    Cursor.FIELD_TYPE_BLOB -> getBlob(index)
                    else -> abort(FinanceDataError.CorruptData)
                },
            )
        }
    }

    private fun SupportSQLiteDatabase.idForUid(table: String, uid: ByteArray): Long? = query(
        "SELECT id FROM $table WHERE uid=?",
        arrayOf(uid),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun SupportSQLiteDatabase.nextId(table: String): Long = Math.addExact(long("SELECT COALESCE(MAX(id),0) FROM $table"), 1L)
    private fun SupportSQLiteDatabase.long(sql: String, argument: Long? = null): Long = query(
        sql,
        argument?.let { arrayOf(it) }.orEmpty(),
    ).use { if (it.moveToFirst()) it.getLong(0) else abort(FinanceDataError.CorruptData) }
    private fun SupportSQLiteDatabase.long(sql: String, arguments: Array<out Any?>): Long = query(sql, arguments).use {
        if (it.moveToFirst()) it.getLong(0) else abort(FinanceDataError.CorruptData)
    }

    private fun SupportSQLiteDatabase.blob(sql: String, argument: Long): ByteArray = query(sql, arrayOf(argument)).use {
        if (it.moveToFirst()) it.getBlob(0) else abort(FinanceDataError.CorruptData)
    }

    private fun SupportSQLiteDatabase.insertRow(table: String, values: Map<String, Any?>, columns: List<String>) {
        execSQL("INSERT INTO $table(${columns.joinToString()}) VALUES(${columns.joinToString { "?" }})", columns.map(values::get).toTypedArray())
    }

    private fun SupportSQLiteDatabase.insertOrIgnoreRow(table: String, values: Map<String, Any?>, columns: List<String>) {
        execSQL("INSERT OR IGNORE INTO $table(${columns.joinToString()}) VALUES(${columns.joinToString { "?" }})", columns.map(values::get).toTypedArray())
    }

    private fun SupportSQLiteDatabase.updateRow(table: String, values: Map<String, Any?>, columns: List<String>) {
        execSQL("UPDATE $table SET ${columns.joinToString { "$it=?" }} WHERE id=?", (columns.map(values::get) + values.getValue("id")).toTypedArray())
    }

    private companion object {
        val SAFE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val ENTITY_TABLES = mapOf(
            EntityType.ACCOUNT to "user_account",
            EntityType.CARD to "payment_card",
            EntityType.CATEGORY to "category",
            EntityType.MERCHANT to "merchant",
            EntityType.PLACE to "place",
            EntityType.TRANSACTION to "business_transaction",
            EntityType.PROJECT to "project",
            EntityType.GOAL to "goal",
            EntityType.BUDGET to "budget_month",
            EntityType.CREDIT_STATEMENT to "credit_statement",
            EntityType.INSTALLMENT_PLAN to "installment_plan",
            EntityType.LOAN to "loan_contract",
            EntityType.PARTICIPANT to "participant",
            EntityType.SETTLEMENT_ACTIVITY to "settlement_activity",
            EntityType.BLUEPRINT to "transaction_blueprint",
            EntityType.RECURRENCE_SERIES to "recurrence_series",
            EntityType.ATTACHMENT to "attachment",
            EntityType.BLOB to "encrypted_blob",
            EntityType.LOCATION_RECORD to "location_record",
            EntityType.BUDGET_TEMPLATE to "budget_template",
        )
        val FORWARD_FACT_TABLES = setOf(
            "transaction_revision",
            "expense_revision_detail",
            "income_revision_detail",
            "transfer_revision_detail",
            "refund_revision_detail",
            "credit_payment_revision_detail",
            "loan_disbursement_revision_detail",
            "loan_payment_revision_detail",
            "balance_adjustment_revision_detail",
            "fx_exchange_revision_detail",
            "settlement_payment_revision_detail",
            "opening_balance_revision_detail",
            "revision_amount",
            "transaction_revision_attachment",
            "transaction_revision_settlement_share",
            "journal_entry",
            "posting",
            "economic_effect",
            "budget_effect",
            "project_effect",
            "goal_effect",
            "statement_effect",
            "loan_effect",
            "settlement_effect",
            "refund_allocation",
            "credit_payment_allocation",
            "loan_actual_allocation",
            "installment_refund_allocation",
        )
    }
}
