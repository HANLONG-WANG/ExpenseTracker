@file:Suppress("TooManyFunctions")

package app.ledger.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

internal object LedgerSchemaDefinition {
    const val PRIMARY_VERSION: Int = 7
    const val STAGING_VERSION: Int = 1

    internal val primaryV1Assets: List<String> = listOf(
        "ledger_schema_v1_core.sql",
        "ledger_schema_v1_subledgers.sql",
        "ledger_schema_v1_projections_operations.sql",
        "ledger_schema_v1_indices_views.sql",
    )

    private val primaryV2Assets: List<String> = primaryV1Assets + listOf(
        "ledger_schema_v2_analytics_configuration.sql",
    )

    private val primaryV3Assets: List<String> = primaryV2Assets + listOf(
        "ledger_schema_v3_widget_snapshot.sql",
    )

    private val primaryV4Assets: List<String> = primaryV3Assets + listOf(
        "ledger_schema_v4_projection_generation.sql",
    )

    private val primaryV5Assets: List<String> = primaryV4Assets + listOf(
        "ledger_schema_v5_architecture_alignment.sql",
    )

    private val primaryV6Assets: List<String> = primaryV5Assets + listOf(
        "ledger_schema_v6_journal_keyset.sql",
    )

    val primaryAssets: List<String> = primaryV6Assets + listOf(
        "ledger_schema_v7_reference_keysets.sql",
    )

    val stagingAssets: List<String> = listOf("import_staging_schema_v1.sql")

    val immutableTables: Set<String> = setOf(
        "book_commit",
        "book_commit_parent",
        "command_receipt",
        "entity_change",
        "entity_revision",
        "account_balance_checkpoint",
        "location_record",
        "transaction_revision",
        "revision_amount",
        "fx_rate_snapshot",
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
        "credit_limit_period",
        "credit_statement_revision",
        "credit_payment_allocation",
        "installment_plan_revision",
        "installment_schedule_revision",
        "installment_schedule_item",
        "installment_refund_allocation",
        "loan_terms_revision",
        "loan_rate_period",
        "loan_schedule_revision",
        "loan_schedule_item",
        "loan_actual_allocation",
        "settlement_payment_record",
        "goal_movement",
        "budget_template_revision",
        "budget_template_category_limit",
        "budget_month_revision",
        "budget_category_limit",
        "budget_adjustment",
        "transaction_blueprint_revision",
        "blueprint_settlement_share_rule",
        "recurrence_series_revision",
        "recurrence_rule_weekday",
        "recurrence_exception",
        "import_batch_commit",
        "import_source_reference",
        "backup_snapshot",
        "backup_object",
        "backup_snapshot_object",
    )

    fun installPrimary(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, primaryAssets)
        immutableTables.sorted().forEach { table -> installAppendOnlyTriggers(database, table) }
        registerPrimaryContract(context, database)
    }

    fun installStaging(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, stagingAssets)
        registerStagingContract(context, database)
    }

    fun migratePrimaryV1ToV2(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, listOf("ledger_schema_v2_analytics_configuration.sql"))
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?, contractSha256=? WHERE id=1",
            arrayOf<Any>(2, primaryV2ContractSha256(context)),
        )
    }

    fun migratePrimaryV2ToV3(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, listOf("ledger_schema_v3_widget_snapshot.sql"))
        database.execSQL("DELETE FROM widget_goal_snapshot")
        database.execSQL("DELETE FROM widget_credit_snapshot")
        database.execSQL("DELETE FROM widget_account_snapshot")
        database.execSQL("DELETE FROM widget_book_snapshot")
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?, contractSha256=? WHERE id=1",
            arrayOf<Any>(PRIMARY_V3_VERSION, primaryV3ContractSha256(context)),
        )
    }

    fun migratePrimaryV3ToV4(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, listOf("ledger_schema_v4_projection_generation.sql"))
        database.execSQL(
            "INSERT INTO projection_family_state(family,as_of_local_revision,as_of_valuation_revision) " +
                "SELECT value,b.local_revision,b.valuation_revision FROM book b " +
                "JOIN (SELECT 0 value UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL " +
                "SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL " +
                "SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL " +
                "SELECT $LAST_PROJECTION_FAMILY_ID) " +
                "WHERE b.id=1",
        )
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?, contractSha256=? WHERE id=1",
            arrayOf<Any>(PRIMARY_V4_VERSION, primaryV4ContractSha256(context)),
        )
    }

    fun migratePrimaryV4ToV5(context: Context, database: SupportSQLiteDatabase) {
        immutableTables.sorted().forEach { table ->
            database.execSQL("DROP TRIGGER IF EXISTS ${table}_reject_update")
            database.execSQL("DROP TRIGGER IF EXISTS ${table}_reject_delete")
        }
        database.execSQL("DROP TRIGGER IF EXISTS purge_tombstone_reject_update")
        database.execSQL("DROP TRIGGER IF EXISTS purge_tombstone_reject_delete")
        SchemaSqlAssets.install(context, database, listOf("ledger_schema_v5_architecture_alignment.sql"))
        immutableTables.sorted().forEach { table ->
            installAppendOnlyTriggers(database, table)
        }
        database.execSQL("DROP TABLE IF EXISTS _schema_runtime_guard")
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?, contractSha256=? WHERE id=1",
            arrayOf<Any>(PRIMARY_V5_VERSION, primaryV5ContractSha256(context)),
        )
    }

    fun migratePrimaryV5ToV6(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, listOf("ledger_schema_v6_journal_keyset.sql"))
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?, contractSha256=? WHERE id=1",
            arrayOf<Any>(PRIMARY_V6_VERSION, primaryV6ContractSha256(context)),
        )
    }

    fun migratePrimaryV6ToV7(context: Context, database: SupportSQLiteDatabase) {
        SchemaSqlAssets.install(context, database, listOf("ledger_schema_v7_reference_keysets.sql"))
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?, contractSha256=? WHERE id=1",
            arrayOf<Any>(PRIMARY_VERSION, primaryContractSha256(context)),
        )
    }

    fun primaryContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryAssets)

    internal fun primaryV1ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV1Assets)

    internal fun primaryV2ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV2Assets)

    internal fun primaryV3ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV3Assets)

    internal fun primaryV4ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV4Assets)

    internal fun primaryV5ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV5Assets)

    internal fun primaryV6ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV6Assets)

    internal fun primaryV1Statements(context: Context): List<String> = SchemaSqlAssets.statements(context, primaryV1Assets)

    internal fun expectedPrimaryV2TableNames(context: Context): Set<String> = SchemaSqlAssets.statements(
        context,
        listOf("ledger_schema_v2_analytics_configuration.sql"),
    ).mapNotNull(SchemaSqlAssets::createdTableName).toSet()

    fun stagingContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, stagingAssets)

    fun expectedPrimaryTableNames(context: Context): Set<String> = SchemaSqlAssets.statements(context, primaryAssets).mapNotNull(SchemaSqlAssets::createdTableName).toSet()

    fun expectedPrimaryViewNames(context: Context): Set<String> = SchemaSqlAssets.statements(context, primaryAssets).mapNotNull(SchemaSqlAssets::createdViewName).toSet()

    fun expectedStagingTableNames(context: Context): Set<String> = SchemaSqlAssets.statements(context, stagingAssets).mapNotNull(SchemaSqlAssets::createdTableName).toSet()

    private fun installAppendOnlyTriggers(database: SupportSQLiteDatabase, table: String) {
        database.execSQL(
            "CREATE TRIGGER ${table}_reject_update BEFORE UPDATE ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'immutable table update rejected'); END",
        )
        if (table !in retentionManagedTables) {
            database.execSQL(
                "CREATE TRIGGER ${table}_reject_delete BEFORE DELETE ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'immutable table delete rejected'); END",
            )
        }
    }

    private fun registerPrimaryContract(context: Context, database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO _room_schema_registry(id, logicalSchemaVersion, contractSha256) VALUES (1, ?, ?)",
            arrayOf<Any>(PRIMARY_VERSION, primaryContractSha256(context)),
        )
    }

    private fun registerStagingContract(context: Context, database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO _staging_room_schema_registry(id, logicalSchemaVersion, contractSha256) VALUES (1, ?, ?)",
            arrayOf<Any>(STAGING_VERSION, stagingContractSha256(context)),
        )
    }
}

private val retentionManagedTables = setOf("backup_snapshot", "backup_object", "backup_snapshot_object")

private const val LAST_PROJECTION_FAMILY_ID = 14
private const val PRIMARY_V3_VERSION = 3
private const val PRIMARY_V4_VERSION = 4
private const val PRIMARY_V5_VERSION = 5
private const val PRIMARY_V6_VERSION = 6

private object SchemaSqlAssets {
    fun install(context: Context, database: SupportSQLiteDatabase, assets: List<String>) {
        statements(context, assets).forEach(database::execSQL)
    }

    fun statements(context: Context, assets: List<String>): List<String> = assets.flatMap { readStatements(context, it) }

    fun createdTableName(statement: String): String? = CREATE_TABLE.find(statement)?.groupValues?.get(1)

    fun createdViewName(statement: String): String? = CREATE_VIEW.find(statement)?.groupValues?.get(1)

    fun contractSha256(context: Context, assets: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.forEach { name ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            updateDigestFromAsset(digest, context, name)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun readStatements(context: Context, asset: String): List<String> = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText()
            .split(STATEMENT_DELIMITER)
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun updateDigestFromAsset(digest: MessageDigest, context: Context, name: String) {
        context.assets.open(name).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var count = stream.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
    }

    private const val STATEMENT_DELIMITER = "--@@"
    private const val BUFFER_SIZE = 8192
    private val CREATE_TABLE = Regex("(?i)CREATE\\s+(?:VIRTUAL\\s+)?TABLE\\s+([a-z0-9_]+)")
    private val CREATE_VIEW = Regex("(?i)CREATE\\s+VIEW\\s+([a-z0-9_]+)")
}
