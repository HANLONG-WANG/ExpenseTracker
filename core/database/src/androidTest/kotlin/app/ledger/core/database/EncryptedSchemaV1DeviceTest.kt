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
    fun schemaVersionFiveHasRegisteredNonDestructivePredecessorMigrations() {
        assertEquals(5, LedgerMigrations.CURRENT_VERSION)
        assertEquals(4, LedgerMigrations.registered(context).size)
        assertEquals(4, LedgerMigrations.contracts.size)
        assertEquals(1, StagingMigrations.CURRENT_VERSION)
        assertTrue(StagingMigrations.registered.isEmpty())
        assertTrue(StagingMigrations.contracts.isEmpty())
    }

    @Test
    fun everyEncryptedPredecessorMigratesToVersionFiveWithFinancialAndQueryContractsIntact() {
        (1..4).forEach { predecessor ->
            context.deleteDatabase(PRIMARY_NAME)
            val passphrase = passphrase(0x60 + predecessor)
            val initial = EncryptedDatabaseFactory.openPrimary(context, passphrase)
            val current = initial.openHelper.writableDatabase
            seedMigrationLedger(current)
            recreatePredecessorSurface(current, predecessor)
            initial.close()

            val migrated = EncryptedDatabaseFactory.openPrimary(context, passphrase)
            val database = migrated.openHelper.writableDatabase
            assertEquals(5L, singleLong(database, "PRAGMA user_version"))
            assertEquals(1L, singleLong(database, "SELECT count(*) FROM book WHERE base_currency='JPY'"))
            assertEquals(5L, singleLong(database, "SELECT logicalSchemaVersion FROM _room_schema_registry WHERE id=1"))
            assertEquals(
                LedgerSchemaDefinition.primaryContractSha256(context),
                singleString(database, "SELECT contractSha256 FROM _room_schema_registry WHERE id=1"),
            )
            assertTrue(sqliteObjectNames(database, "table").containsAll(LedgerSchemaDefinition.expectedPrimaryV2TableNames(context)))
            assertTrue(columnNames(database, "widget_book_snapshot").contains("today_available_base_minor"))
            assertTrue(columnNames(database, "widget_book_snapshot").contains("previous_core_net_financial_assets_base_minor"))
            assertEquals(15L, singleLong(database, "SELECT COUNT(*) FROM projection_family_state"))
            assertEquals(0L, singleLong(database, "SELECT COUNT(*) FROM journal_balance_audit WHERE is_balanced=0"))
            assertEquals(
                1L,
                singleLong(
                    database,
                    "SELECT COUNT(*) FROM credit_account_profile cap WHERE cap.row_version=1 " +
                        "AND cap.content_hash=(SELECT after_hash FROM entity_change WHERE commit_id=1 AND entity_type=1)",
                ),
            )
            assertEquals(
                1L,
                singleLong(
                    database,
                    "SELECT COUNT(*) FROM budget_month bm WHERE bm.last_commit_id=1 AND bm.row_version=1 " +
                        "AND bm.content_hash=(SELECT payload_hash FROM command_receipt WHERE commit_id=1)",
                ),
            )

            AnalyticsProjectionEngine.rebuild(database, 1L)
            assertTrue(AnalyticsProjectionEngine.audit(database, 1L).consistent)
            assertEquals(1L, singleLong(database, "SELECT COUNT(*) FROM transaction_fts WHERE transaction_fts MATCH 'migration'"))
            assertEquals(1L, singleLong(database, "SELECT COUNT(*) FROM location_rtree WHERE min_lat<=35.05 AND max_lat>=35.05"))
            assertTrue(DatabaseIntegrityAudit.run(database).isValid)
            migrated.close()

            val reopened = EncryptedDatabaseFactory.openPrimary(context, passphrase)
            assertEquals(1L, singleLong(reopened.openHelper.writableDatabase, "SELECT count(*) FROM book"))
            reopened.close()
        }
    }

    private fun recreatePredecessorSurface(database: SupportSQLiteDatabase, version: Int) {
        require(version in 1..4)
        database.setForeignKeyConstraintsEnabled(false)
        LedgerSchemaDefinition.immutableTables.forEach { table ->
            database.execSQL("DROP TRIGGER IF EXISTS ${table}_reject_update")
            database.execSQL("DROP TRIGGER IF EXISTS ${table}_reject_delete")
        }
        database.execSQL("DROP VIEW IF EXISTS budget_effect_line")
        database.execSQL("DROP TABLE projection_contract_state")
        V5_COLUMNS.forEach { (table, column) -> database.execSQL("ALTER TABLE $table DROP COLUMN $column") }
        database.execSQL(
            "CREATE TABLE _schema_runtime_guard(id INTEGER PRIMARY KEY CHECK(id=1),allow_fact_purge INTEGER NOT NULL CHECK(allow_fact_purge IN(0,1)))",
        )
        database.execSQL("INSERT INTO _schema_runtime_guard(id,allow_fact_purge) VALUES(1,0)")

        if (version < 4) database.execSQL("DROP TABLE projection_family_state")
        if (version < 3) {
            V3_WIDGET_COLUMNS.forEach { (table, column) -> database.execSQL("ALTER TABLE $table DROP COLUMN $column") }
        }
        if (version < 2) {
            LedgerSchemaDefinition.expectedPrimaryV2TableNames(context).sortedDescending().forEach { table ->
                database.execSQL("DROP TABLE $table")
            }
        }
        val contract = when (version) {
            1 -> LedgerSchemaDefinition.primaryV1ContractSha256(context)
            2 -> LedgerSchemaDefinition.primaryV2ContractSha256(context)
            3 -> LedgerSchemaDefinition.primaryV3ContractSha256(context)
            else -> LedgerSchemaDefinition.primaryV4ContractSha256(context)
        }
        database.execSQL(
            "UPDATE _room_schema_registry SET logicalSchemaVersion=?,contractSha256=? WHERE id=1",
            arrayOf<Any>(version, contract),
        )
        database.execSQL("PRAGMA user_version=$version")
    }

    private fun seedMigrationLedger(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO rule_set_version(version,algorithm_hash,activated_at) VALUES(1,?,1)", arrayOf<Any>(blob(1, 32)))
        database.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) VALUES(1,?,1,0,?,?,1,?)",
            arrayOf<Any>(blob(2, 16), blob(3, 16), blob(4, 16), blob(5, 32)),
        )
        database.execSQL(
            "INSERT INTO book(id,uid,base_currency,default_zone_id,head_commit_id,local_revision,valuation_revision,rule_set_version,created_at,first_financial_commit_at,state) " +
                "VALUES(1,?,'JPY','Asia/Tokyo',1,1,1,1,1,1,0)",
            arrayOf<Any>(blob(6, 16)),
        )
        database.execSQL(
            "INSERT INTO ledger_account(id,uid,owner_type,account_class,normal_side,currency_code,system_code,status,created_commit_id) VALUES(1,?,0,0,0,'JPY',NULL,0,1)",
            arrayOf<Any>(blob(7, 16)),
        )
        database.execSQL(
            "INSERT INTO ledger_account(id,uid,owner_type,account_class,normal_side,currency_code,system_code,status,created_commit_id) VALUES(2,?,1,3,1,'JPY','SYSTEM_EXPENSE',0,1)",
            arrayOf<Any>(blob(8, 16)),
        )
        database.execSQL(
            "INSERT INTO user_account(id,uid,ledger_account_id,type,name,currency_code,status,icon_key,color_argb,sort_order,last_commit_id,row_version,content_hash) " +
                "VALUES(1,?,1,0,'Cash','JPY',0,'cash',-1,0,1,1,?)",
            arrayOf<Any>(blob(9, 16), blob(10, 32)),
        )
        database.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) VALUES(2,?,2,0,?,?,2,?)",
            arrayOf<Any>(blob(21, 16), blob(22, 16), blob(23, 16), blob(24, 32)),
        )
        database.execSQL(
            "INSERT INTO entity_change(commit_id,entity_type,entity_uid,operation,before_hash,after_hash,entity_revision_uid) " +
                "VALUES(1,1,?,0,NULL,?,NULL),(2,1,?,1,?,?,NULL)",
            arrayOf<Any>(blob(9, 16), blob(10, 32), blob(9, 16), blob(10, 32), blob(25, 32)),
        )
        database.execSQL(
            "INSERT INTO credit_account_profile(account_id,statement_rule_type,statement_day,due_rule_type,due_day,days_after_statement," +
                "zone_id,standard_limit_minor,temporary_limit_minor,temporary_limit_expires_on,default_payment_account_id," +
                "auto_payment_mode,weekend_adjustment,last_commit_id) VALUES(1,0,25,0,10,NULL,'Asia/Tokyo',100000,NULL,NULL,NULL,0,0,1)",
        )
        database.execSQL(
            "INSERT INTO command_receipt(command_uid,command_type,payload_hash,commit_id,primary_entity_uid,executed_at) " +
                "VALUES(?,0,?,1,?,1)",
            arrayOf<Any>(blob(40, 16), blob(41, 32), blob(42, 16)),
        )
        database.execSQL(
            "INSERT INTO budget_month(id,uid,year_month,current_revision_id) VALUES(1,?,202608,NULL)",
            arrayOf<Any>(blob(42, 16)),
        )
        database.execSQL(
            "INSERT INTO budget_month_revision(id,uid,budget_month_id,revision_no,base_total_minor,source_template_revision_id,created_commit_id) " +
                "VALUES(1,?,1,1,100000,NULL,1)",
            arrayOf<Any>(blob(43, 16)),
        )
        database.execSQL("UPDATE budget_month SET current_revision_id=1 WHERE id=1")
        database.execSQL(
            "INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,created_commit_id) VALUES(1,?,350500000,1390500000,1000,1,0,'migration',1)",
            arrayOf<Any>(blob(11, 16)),
        )
        database.execSQL("INSERT INTO location_rtree(location_id,min_lat,max_lat,min_lon,max_lon) VALUES(1,35.05,35.05,139.05,139.05)")
        database.execSQL(
            "INSERT INTO business_transaction(id,uid,kind,current_revision_id,lifecycle_state,created_commit_id,last_commit_id,row_version,content_hash) VALUES(1,?,0,NULL,0,1,1,1,?)",
            arrayOf<Any>(blob(12, 16), blob(13, 32)),
        )
        database.execSQL(
            "INSERT INTO transaction_revision(id,uid,transaction_id,revision_no,action,resulting_state,created_commit_id,created_at,occurred_at,zone_id,local_date,location_record_id,source_type,content_hash) " +
                "VALUES(1,?,1,1,0,0,1,1,1,'Asia/Tokyo',20260820,1,0,?)",
            arrayOf<Any>(blob(14, 16), blob(15, 32)),
        )
        database.execSQL("INSERT INTO expense_revision_detail(revision_id,payer_kind,payer_account_id) VALUES(1,0,1)")
        database.execSQL("UPDATE business_transaction SET current_revision_id=1 WHERE id=1")
        database.execSQL(
            "INSERT INTO journal_entry(id,uid,source_revision_id,applies_revision_id,entry_role,effective_at,zone_id,local_date,base_currency,base_debit_total_minor,base_credit_total_minor,posting_count,rule_set_version,created_commit_id,content_hash) " +
                "VALUES(1,?,1,1,0,1,'Asia/Tokyo',20260820,'JPY',100,100,2,1,1,?)",
            arrayOf<Any>(blob(16, 16), blob(17, 32)),
        )
        database.execSQL(
            "INSERT INTO posting(id,uid,journal_entry_id,line_no,ledger_account_id,side,account_amount_minor,account_currency,base_amount_minor,base_currency,valuation_source,posting_role) " +
                "VALUES(1,?,1,1,2,0,100,'JPY',100,'JPY',0,3)",
            arrayOf<Any>(blob(18, 16)),
        )
        database.execSQL(
            "INSERT INTO posting(id,uid,journal_entry_id,line_no,ledger_account_id,side,account_amount_minor,account_currency,base_amount_minor,base_currency,valuation_source,posting_role) " +
                "VALUES(2,?,1,2,1,1,100,'JPY',100,'JPY',0,0)",
            arrayOf<Any>(blob(19, 16)),
        )
        database.execSQL(
            "INSERT INTO economic_effect(id,uid,source_revision_id,polarity,nature,component,is_consumption,base_amount_minor,accrual_local_date,rule_set_version) VALUES(1,?,1,1,1,0,1,100,20260820,1)",
            arrayOf<Any>(blob(20, 16)),
        )
        database.execSQL(
            "INSERT INTO transaction_fts(transaction_id,category_name,merchant_name,merchant_aliases,note,project_name,settlement_activity_name,participant_names,attachment_names,lifecycle_state) " +
                "VALUES(1,'','','','migration','','','','',0)",
        )
        database.execSQL(
            "WITH RECURSIVE family(value) AS (SELECT 0 UNION ALL SELECT value+1 FROM family WHERE value<14) " +
                "INSERT INTO projection_family_state(family,as_of_local_revision,as_of_valuation_revision) SELECT value,1,1 FROM family",
        )
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
            if (table in RETENTION_MANAGED_TABLES) {
                assertFalse(sqliteObjectNames(database, "trigger").contains("${table}_reject_delete"))
            } else {
                assertTrue(sqliteObjectNames(database, "trigger").contains("${table}_reject_delete"))
            }
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

        val RETENTION_MANAGED_TABLES = setOf("backup_snapshot", "backup_object", "backup_snapshot_object")
        val V5_COLUMNS = listOf(
            "posting" to "valuation_source",
            "payment_card" to "content_hash", "category" to "content_hash", "merchant" to "content_hash",
            "place" to "content_hash", "project" to "content_hash", "goal" to "content_hash",
            "participant" to "row_version", "participant" to "content_hash",
            "settlement_activity" to "row_version", "settlement_activity" to "content_hash",
            "transaction_blueprint" to "last_commit_id", "transaction_blueprint" to "row_version", "transaction_blueprint" to "content_hash",
            "recurrence_series" to "last_commit_id", "recurrence_series" to "row_version", "recurrence_series" to "content_hash",
            "budget_template" to "last_commit_id", "budget_template" to "row_version", "budget_template" to "content_hash",
            "budget_month" to "last_commit_id", "budget_month" to "row_version", "budget_month" to "content_hash",
            "credit_statement" to "last_commit_id", "credit_statement" to "row_version", "credit_statement" to "content_hash",
            "installment_plan" to "last_commit_id", "installment_plan" to "row_version", "installment_plan" to "content_hash",
            "loan_contract" to "row_version", "loan_contract" to "content_hash",
            "credit_account_profile" to "row_version", "credit_account_profile" to "content_hash",
        )
        val V3_WIDGET_COLUMNS = listOf(
            "widget_book_snapshot" to "snapshot_local_date", "widget_book_snapshot" to "month_key",
            "widget_book_snapshot" to "month_consumption_base_minor", "widget_book_snapshot" to "previous_month_consumption_base_minor",
            "widget_book_snapshot" to "month_budget_available_base_minor", "widget_book_snapshot" to "month_budget_used_base_minor",
            "widget_book_snapshot" to "today_available_base_minor", "widget_book_snapshot" to "previous_core_net_financial_assets_base_minor",
            "widget_account_snapshot" to "account_uid", "widget_account_snapshot" to "display_name", "widget_account_snapshot" to "available_minor",
            "widget_credit_snapshot" to "account_uid", "widget_credit_snapshot" to "display_name",
            "widget_credit_snapshot" to "statement_remaining_minor", "widget_credit_snapshot" to "statement_due_date",
            "widget_goal_snapshot" to "goal_uid", "widget_goal_snapshot" to "display_name",
        )

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
