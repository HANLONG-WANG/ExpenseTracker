@file:Suppress("TooManyFunctions")

package app.ledger.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

internal object LedgerSchemaDefinition {
    const val PRIMARY_VERSION: Int = 2
    const val STAGING_VERSION: Int = 1

    internal val primaryV1Assets: List<String> = listOf(
        "ledger_schema_v1_core.sql",
        "ledger_schema_v1_subledgers.sql",
        "ledger_schema_v1_projections_operations.sql",
        "ledger_schema_v1_indices_views.sql",
    )

    val primaryAssets: List<String> = primaryV1Assets + listOf(
        "ledger_schema_v2_analytics_configuration.sql",
    )

    val stagingAssets: List<String> = listOf("import_staging_schema_v1.sql")

    val immutableTables: Set<String> = setOf(
        "book_commit",
        "book_commit_parent",
        "command_receipt",
        "entity_change",
        "entity_revision",
        "purge_tombstone",
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
        database.execSQL(
            "CREATE TABLE _schema_runtime_guard (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "allow_fact_purge INTEGER NOT NULL CHECK (allow_fact_purge IN (0,1)))",
        )
        database.execSQL("INSERT INTO _schema_runtime_guard(id, allow_fact_purge) VALUES (1, 0)")
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
            arrayOf<Any>(PRIMARY_VERSION, primaryContractSha256(context)),
        )
    }

    fun primaryContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryAssets)

    internal fun primaryV1ContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, primaryV1Assets)

    internal fun expectedPrimaryV2TableNames(context: Context): Set<String> = SchemaSqlAssets.statements(
        context,
        listOf("ledger_schema_v2_analytics_configuration.sql"),
    ).mapNotNull(SchemaSqlAssets::createdTableName).toSet()

    fun stagingContractSha256(context: Context): String = SchemaSqlAssets.contractSha256(context, stagingAssets)

    fun expectedPrimaryTableNames(context: Context): Set<String> = SchemaSqlAssets.statements(context, primaryAssets).mapNotNull(SchemaSqlAssets::createdTableName).toSet() +
        "_schema_runtime_guard"

    fun expectedPrimaryViewNames(context: Context): Set<String> = SchemaSqlAssets.statements(context, primaryAssets).mapNotNull(SchemaSqlAssets::createdViewName).toSet()

    fun expectedStagingTableNames(context: Context): Set<String> = SchemaSqlAssets.statements(context, stagingAssets).mapNotNull(SchemaSqlAssets::createdTableName).toSet()

    private fun installAppendOnlyTriggers(database: SupportSQLiteDatabase, table: String) {
        database.execSQL(
            "CREATE TRIGGER ${table}_reject_update BEFORE UPDATE ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'immutable table update rejected'); END",
        )
        database.execSQL(
            "CREATE TRIGGER ${table}_reject_delete BEFORE DELETE ON $table " +
                "WHEN NOT (" +
                "EXISTS(SELECT 1 FROM book WHERE id = 1 AND state = 1) AND " +
                "EXISTS(SELECT 1 FROM _schema_runtime_guard WHERE id = 1 AND allow_fact_purge = 1)" +
                ") BEGIN SELECT RAISE(ABORT, 'immutable table delete rejected'); END",
        )
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
