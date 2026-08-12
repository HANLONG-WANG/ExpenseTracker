package app.ledger.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedSchemaV1DeviceTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(PRIMARY_NAME)
        context.deleteDatabase(STAGING_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(PRIMARY_NAME)
        context.deleteDatabase(STAGING_NAME)
    }

    @Test
    fun encryptedPrimaryDatabaseCreatesAuditsClosesAndReopens() {
        val passphrase = passphrase(0x31)
        val first = EncryptedDatabaseFactory.openPrimary(context, passphrase)
        val database = first.openHelper.writableDatabase

        assertEquals("wal", singleString(database, "PRAGMA journal_mode").lowercase())
        assertEquals(1L, singleLong(database, "PRAGMA foreign_keys"))
        assertEquals(2L, singleLong(database, "PRAGMA temp_store"))
        assertEquals(2L, singleLong(database, "PRAGMA auto_vacuum"))
        assertSchemaObjects(database)

        val capability = DatabaseIntegrityAudit.capabilityReport(database)
        assertTrue(capability.sqlCipherVersion.startsWith("4.17.0"))
        assertTrue(capability.fts5)
        assertTrue(capability.rTree)
        assertTrue(capability.json)
        assertTrue(capability.windowFunctions)

        database.execSQL(
            "INSERT INTO transaction_fts(transaction_id, category_name, merchant_name, merchant_aliases, note, " +
                "project_name, settlement_activity_name, participant_names, attachment_names, lifecycle_state) " +
                "VALUES (1, '食事', '店舗', 'alias', ?, 'project', 'activity', 'participant', 'attachment', 0)",
            arrayOf<Any>(SENSITIVE_SENTINEL),
        )
        assertEquals(1L, singleLong(database, "SELECT count(*) FROM transaction_fts WHERE transaction_fts MATCH 'secret'"))
        database.execSQL("INSERT INTO location_rtree VALUES (1, 35.0, 35.1, 139.0, 139.1)")
        assertEquals(1L, singleLong(database, "SELECT count(*) FROM location_rtree WHERE min_lat <= 35.05 AND max_lat >= 35.05"))

        insertAppendOnlyCommit(database)
        assertThrows(Exception::class.java) { database.execSQL("UPDATE book_commit SET kind = 2 WHERE id = 1") }
        assertThrows(Exception::class.java) {
            database.execSQL(
                "INSERT INTO book(id, uid, base_currency, default_zone_id, local_revision, valuation_revision, " +
                    "rule_set_version, created_at, state) VALUES (2, zeroblob(16), 'JPY', 'Asia/Tokyo', 0, 0, 1, 1, 0)",
            )
        }

        val audit = DatabaseIntegrityAudit.run(database)
        assertTrue(audit.isValid)
        val checkpoint = DatabaseMaintenance.checkpoint(database, WalCheckpointMode.PASSIVE)
        assertTrue(checkpoint.busyConnections >= 0)
        assertTrue(checkpoint.walFrames >= 0)
        assertTrue(checkpoint.checkpointedFrames >= 0)
        DatabaseMaintenance.incrementalVacuum(database, 16)
        DatabaseMaintenance.optimize(database)

        assertEncryptedFilesDoNotContain(databaseFiles(PRIMARY_NAME), SENSITIVE_SENTINEL)
        first.close()

        val reopened = EncryptedDatabaseFactory.openPrimary(context, passphrase)
        val reopenedDatabase = reopened.openHelper.writableDatabase
        assertEquals(1L, singleLong(reopenedDatabase, "SELECT count(*) FROM transaction_fts WHERE transaction_fts MATCH 'secret'"))
        assertTrue(DatabaseIntegrityAudit.run(reopenedDatabase).isValid)
        reopened.close()

        val wrong = EncryptedDatabaseFactory.openPrimary(context, passphrase(0x55))
        assertThrows(Exception::class.java) { wrong.openHelper.writableDatabase }
        wrong.close()
        assertEncryptedFilesDoNotContain(databaseFiles(PRIMARY_NAME), SENSITIVE_SENTINEL)
    }

    @Test
    fun encryptedStagingDatabaseIsIndependentAndReopenable() {
        val passphrase = passphrase(0x42)
        val staging = EncryptedDatabaseFactory.openImportStaging(context, STAGING_NAME, passphrase)
        val database = staging.openHelper.writableDatabase
        val expected = LedgerSchemaDefinition.expectedStagingTableNames(context)
        val actual = sqliteObjectNames(database, "table")
        assertTrue(actual.containsAll(expected))
        assertEquals(7, expected.size)
        database.execSQL(
            "INSERT INTO staging_raw_row(row_number, payload, source_hash, ingestion_state) VALUES (1, ?, zeroblob(32), 0)",
            arrayOf<Any>(SENSITIVE_SENTINEL.toByteArray()),
        )
        assertEncryptedFilesDoNotContain(databaseFiles(STAGING_NAME), SENSITIVE_SENTINEL)
        staging.close()

        val reopened = EncryptedDatabaseFactory.openImportStaging(context, STAGING_NAME, passphrase)
        assertEquals(1L, singleLong(reopened.openHelper.writableDatabase, "SELECT count(*) FROM staging_raw_row"))
        reopened.close()
        assertEncryptedFilesDoNotContain(databaseFiles(STAGING_NAME), SENSITIVE_SENTINEL)
    }

    @Test
    fun schemaVersionFourHasRegisteredNonDestructivePredecessorMigrations() {
        assertEquals(4, LedgerMigrations.CURRENT_VERSION)
        assertEquals(3, LedgerMigrations.registered(context).size)
        assertEquals(3, LedgerMigrations.contracts.size)
        assertEquals(1, StagingMigrations.CURRENT_VERSION)
        assertTrue(StagingMigrations.registered.isEmpty())
        assertTrue(StagingMigrations.contracts.isEmpty())
    }

    @Test
    fun encryptedVersionOneDatabaseMigratesToVersionFourWithoutLosingLedgerData() {
        val passphrase = passphrase(0x63)
        val initial = EncryptedDatabaseFactory.openPrimary(context, passphrase)
        val current = initial.openHelper.writableDatabase
        current.execSQL(
            "INSERT INTO book(id, uid, base_currency, default_zone_id, local_revision, valuation_revision, " +
                "rule_set_version, created_at, state) VALUES (1, ?, 'JPY', 'Asia/Tokyo', 0, 0, 1, 1, 0)",
            arrayOf<Any>(blob(33, 16)),
        )

        // Recreate the exact predecessor surface from the current encrypted file. This avoids any
        // plaintext test database and proves Room applies the registered SQLCipher migration.
        current.setForeignKeyConstraintsEnabled(false)
        LedgerSchemaDefinition.expectedPrimaryV2TableNames(context).forEach { table ->
            current.execSQL("DROP TABLE $table")
        }
        listOf("widget_goal_snapshot", "widget_credit_snapshot", "widget_account_snapshot", "widget_book_snapshot").forEach {
            current.execSQL("DROP TABLE $it")
        }
        current.execSQL("DROP TABLE projection_family_state")
        LedgerSchemaDefinition.primaryV1Statements(context)
            .filter { statement -> statement.startsWith("CREATE TABLE widget_") }
            .forEach(current::execSQL)
        current.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=1, contractSha256=? WHERE id=1",
            arrayOf<Any>(LedgerSchemaDefinition.primaryV1ContractSha256(context)),
        )
        current.execSQL("PRAGMA user_version = 1")
        initial.close()

        val migrated = EncryptedDatabaseFactory.openPrimary(context, passphrase)
        val database = migrated.openHelper.writableDatabase
        assertEquals(4L, singleLong(database, "PRAGMA user_version"))
        assertEquals(1L, singleLong(database, "SELECT count(*) FROM book WHERE base_currency='JPY'"))
        assertEquals(4L, singleLong(database, "SELECT logicalSchemaVersion FROM _room_schema_registry WHERE id=1"))
        assertEquals(
            LedgerSchemaDefinition.primaryContractSha256(context),
            singleString(database, "SELECT contractSha256 FROM _room_schema_registry WHERE id=1"),
        )
        assertTrue(sqliteObjectNames(database, "table").containsAll(LedgerSchemaDefinition.expectedPrimaryV2TableNames(context)))
        assertTrue(columnNames(database, "widget_book_snapshot").contains("today_available_base_minor"))
        assertTrue(columnNames(database, "widget_book_snapshot").contains("previous_core_net_financial_assets_base_minor"))
        assertEquals(15L, singleLong(database, "SELECT COUNT(*) FROM projection_family_state"))
        assertTrue(DatabaseIntegrityAudit.run(database).isValid)
        migrated.close()

        val reopened = EncryptedDatabaseFactory.openPrimary(context, passphrase)
        assertEquals(1L, singleLong(reopened.openHelper.writableDatabase, "SELECT count(*) FROM book"))
        reopened.close()
    }

    private fun columnNames(database: SupportSQLiteDatabase, table: String): Set<String> = database.query("PRAGMA table_info($table)").use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }

    private fun assertSchemaObjects(database: SupportSQLiteDatabase) {
        val tables = sqliteObjectNames(database, "table")
        val expectedTables = LedgerSchemaDefinition.expectedPrimaryTableNames(context)
        val expectedViews = LedgerSchemaDefinition.expectedPrimaryViewNames(context)
        assertTrue(tables.containsAll(expectedTables))
        assertTrue(sqliteObjectNames(database, "view").containsAll(expectedViews))
        assertEquals(95, expectedTables.count { it in FROZEN_DOMAIN_TABLES || it == "rule_set_version" })
        assertTrue(FROZEN_DOMAIN_TABLES.all(tables::contains))
        LedgerSchemaDefinition.immutableTables.forEach { table ->
            assertTrue(sqliteObjectNames(database, "trigger").contains("${table}_reject_update"))
            assertTrue(sqliteObjectNames(database, "trigger").contains("${table}_reject_delete"))
        }
    }

    private fun insertAppendOnlyCommit(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO book_commit(id, uid, local_revision, kind, command_uid, device_instance_uid, created_at, root_hash) " +
                "VALUES (1, ?, 1, 0, ?, ?, 1, ?)",
            arrayOf<Any>(blob(1, 16), blob(2, 16), blob(3, 16), blob(4, 32)),
        )
    }

    private fun sqliteObjectNames(
        database: SupportSQLiteDatabase,
        type: String,
    ): Set<String> = database.query("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun databaseFiles(name: String): List<File> {
        val primary = context.getDatabasePath(name)
        val parent = requireNotNull(primary.parentFile)
        return parent.listFiles().orEmpty().filter { it.name == name || it.name.startsWith("$name-") }
    }

    private fun assertEncryptedFilesDoNotContain(files: List<File>, value: String) {
        assertTrue(files.isNotEmpty())
        val needle = value.toByteArray()
        files.filter(File::isFile).forEach { file ->
            assertFalse("plaintext leaked into ${file.name}", file.readBytes().containsSubsequence(needle))
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        var found = false
        for (start in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                found = true
                break
            }
        }
        return found
    }

    private fun singleLong(database: SupportSQLiteDatabase, sql: String): Long = database.query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun singleString(database: SupportSQLiteDatabase, sql: String): String = database.query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun passphrase(seed: Int): ByteArray = ByteArray(32) { index -> (seed + index).toByte() }

    private fun blob(seed: Int, size: Int): ByteArray = ByteArray(size) { index -> (seed + index).toByte() }

    private companion object {
        const val PRIMARY_NAME = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME
        const val STAGING_NAME = "import_00112233445566778899aabbccddeeff.db"
        const val SENSITIVE_SENTINEL = "p07-secret-amount-987654-note"

        val FROZEN_DOMAIN_TABLES = setOf(
            "book", "book_commit", "book_commit_parent", "command_receipt", "entity_change", "entity_revision", "purge_tombstone",
            "ledger_account", "user_account", "payment_card", "card_vault_secret", "account_balance_checkpoint",
            "category", "merchant", "merchant_alias", "place", "location_record", "location_rtree", "place_rtree",
            "business_transaction", "transaction_revision", "revision_amount", "fx_rate_snapshot", "expense_revision_detail",
            "income_revision_detail", "transfer_revision_detail", "refund_revision_detail", "credit_payment_revision_detail",
            "loan_disbursement_revision_detail", "loan_payment_revision_detail", "balance_adjustment_revision_detail",
            "fx_exchange_revision_detail", "settlement_payment_revision_detail", "opening_balance_revision_detail",
            "transaction_revision_attachment", "transaction_revision_settlement_share", "transaction_dependency",
            "journal_entry", "posting", "economic_effect", "budget_effect", "project_effect", "goal_effect", "statement_effect",
            "loan_effect", "settlement_effect", "refund_allocation", "refund_status_projection", "credit_account_profile",
            "credit_limit_period", "credit_statement", "credit_statement_revision", "credit_payment_allocation", "installment_plan",
            "installment_plan_revision", "installment_schedule_revision", "installment_schedule_item", "installment_refund_allocation",
            "loan_contract", "loan_tranche", "loan_terms_revision", "loan_rate_period", "loan_schedule_revision", "loan_schedule_item",
            "loan_actual_allocation", "loan_simulation", "loan_simulation_item", "participant", "settlement_activity",
            "settlement_activity_participant", "settlement_payment_record", "project", "goal", "goal_movement", "budget_template",
            "budget_template_revision", "budget_template_category_limit", "budget_month", "budget_month_revision",
            "budget_category_limit", "budget_adjustment", "budget_rollover", "transaction_blueprint", "transaction_blueprint_revision",
            "blueprint_settlement_share_rule", "recurrence_series", "recurrence_series_revision", "recurrence_rule_weekday",
            "recurrence_exception", "recurrence_occurrence", "recurrence_candidate", "encrypted_blob", "attachment", "blob_gc_candidate",
        )
    }
}
