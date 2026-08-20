package app.ledger.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTestInfrastructureDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LedgerDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(PASSPHRASE.copyOf(), SecureSqlCipherHook, false),
    )

    private lateinit var context: Context

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        SqlCipherNativeLibrary.ensureLoaded()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
        (1..3).forEach { version ->
            context.deleteDatabase("migration-backup-v$version.db")
            context.deleteDatabase(backupRestoreName(version))
        }
    }

    @Test
    fun versionOneToTwoRunsCompletePostMigrationValidation() {
        assertSingleMigrationValidated(fromVersion = 1, toVersion = 2, migrationIndex = 0)
    }

    @Test
    fun versionTwoToThreeRunsCompletePostMigrationValidation() {
        assertSingleMigrationValidated(fromVersion = 2, toVersion = 3, migrationIndex = 1)
    }

    @Test
    fun versionThreeToFourRunsCompletePostMigrationValidation() {
        assertSingleMigrationValidated(fromVersion = 3, toVersion = 4, migrationIndex = 2)
    }

    @Test
    fun encryptedBackupsFromEveryPredecessorRestoreThroughCurrentSchema() {
        (1..3).forEach { version ->
            val predecessorName = "migration-backup-v$version.db"
            val restoredName = backupRestoreName(version)
            context.deleteDatabase(predecessorName)
            context.deleteDatabase(restoredName)
            createLogicalPredecessor(version, predecessorName)
            context.getDatabasePath(predecessorName).copyTo(context.getDatabasePath(restoredName))

            val restored = EncryptedDatabaseFactory.openLedgerCopy(context, restoredName, PASSPHRASE.copyOf())
            val database = restored.openHelper.writableDatabase
            val report = MigrationPostValidation.run(context, database, LedgerMigrations.CURRENT_VERSION)
            assertTrue("backup schema v$version: ${report.failureSummary()}", report.isValid)
            assertEquals(1L, singleLong(database, "SELECT COUNT(*) FROM book WHERE base_currency='JPY'"))
            assertEquals(15L, singleLong(database, "SELECT COUNT(*) FROM projection_family_state"))
            restored.close()
        }
    }

    private fun assertSingleMigrationValidated(fromVersion: Int, toVersion: Int, migrationIndex: Int) {
        createLogicalPredecessor(fromVersion, DATABASE_NAME)
        val migration = LedgerMigrations.registered(context)[migrationIndex]
        val migrated = helper.runMigrationsAndValidate(DATABASE_NAME, toVersion, true, migration)
        val report = MigrationPostValidation.run(context, migrated, toVersion)

        assertTrue(report.failureSummary(), report.isValid)
        assertTrue(report.integrity.capability.sqlCipherVersion.isNotBlank())
        assertTrue(report.integrity.capability.fts5)
        assertTrue(report.integrity.capability.rTree)
        assertTrue(report.representativeQueryUsesIndex)
        assertTrue(report.representativeQueryElapsedMillis <= MigrationPostValidationReport.MAX_REPRESENTATIVE_QUERY_MILLIS)
        assertEquals(DatabaseIntegrityAudit.permanentInvariantIds, report.integrity.permanentInvariantViolationCounts.keys)
        assertTrue(report.integrity.failedInvariantIds.isEmpty())
        assertEquals(toVersion, report.registeredLogicalVersion)
        migrated.close()
    }

    private fun createLogicalPredecessor(version: Int, databaseName: String) {
        val database = helper.createDatabase(databaseName, version)
        LedgerSchemaDefinition.primaryV1Statements(context).forEach(database::execSQL)
        database.execSQL(
            "CREATE TABLE _schema_runtime_guard (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "allow_fact_purge INTEGER NOT NULL CHECK (allow_fact_purge IN (0,1)))",
        )
        database.execSQL("INSERT INTO _schema_runtime_guard(id,allow_fact_purge) VALUES(1,0)")
        LedgerSchemaDefinition.immutableTables.sorted().forEach { table ->
            database.execSQL(
                "CREATE TRIGGER ${table}_reject_update BEFORE UPDATE ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'immutable table update rejected'); END",
            )
            database.execSQL(
                "CREATE TRIGGER ${table}_reject_delete BEFORE DELETE ON $table " +
                    "WHEN NOT (" +
                    "EXISTS(SELECT 1 FROM book WHERE id=1 AND state=1) AND " +
                    "EXISTS(SELECT 1 FROM _schema_runtime_guard WHERE id=1 AND allow_fact_purge=1)" +
                    ") BEGIN SELECT RAISE(ABORT, 'immutable table delete rejected'); END",
            )
        }
        database.execSQL(
            "INSERT INTO _room_schema_registry(id,logicalSchemaVersion,contractSha256) VALUES(1,1,?)",
            arrayOf<Any>(LedgerSchemaDefinition.primaryV1ContractSha256(context)),
        )
        database.execSQL(
            "INSERT INTO rule_set_version(version,algorithm_hash,activated_at,retired_at) VALUES(1,?,0,NULL)",
            arrayOf<Any>(ByteArray(32) { 0x23 }),
        )
        database.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) " +
                "VALUES(1,?,1,0,NULL,?,0,?)",
            arrayOf<Any>(
                ByteArray(16) { index -> (0x41 + index).toByte() },
                ByteArray(16) { index -> (0x51 + index).toByte() },
                ByteArray(32) { 0x61 },
            ),
        )
        database.execSQL(
            "INSERT INTO book(id,uid,base_currency,default_zone_id,head_commit_id,local_revision," +
                "valuation_revision,rule_set_version,created_at,first_financial_commit_at,state) " +
                "VALUES(1,?,'JPY','Asia/Tokyo',1,1,1,1,1,NULL,0)",
            arrayOf<Any>(ByteArray(16) { index -> (0x31 + index).toByte() }),
        )
        if (version >= 2) LedgerSchemaDefinition.migratePrimaryV1ToV2(context, database)
        if (version >= 3) LedgerSchemaDefinition.migratePrimaryV2ToV3(context, database)
        assertEquals(version.toLong(), singleLong(database, "PRAGMA user_version"))
        database.close()
    }

    private fun singleLong(database: SupportSQLiteDatabase, sql: String): Long = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        cursor.getLong(0)
    }

    private companion object {
        const val DATABASE_NAME = "migration-post-validation.db"
        val PASSPHRASE: ByteArray = ByteArray(32) { index -> (0x71 + index).toByte() }

        fun backupRestoreName(version: Int): String =
            "ledger_shadow_${version.toString().padStart(32, '0')}.db"
    }
}
