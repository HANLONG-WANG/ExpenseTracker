package app.ledger.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [PrimarySchemaRegistryEntity::class],
    version = LedgerSchemaDefinition.PRIMARY_VERSION,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    internal abstract fun schemaRegistryDao(): PrimarySchemaRegistryDao

    /** Runs infrastructure work on Room's sole SQLCipher connection and transaction boundary. */
    fun <T> inLedgerTransaction(block: (SupportSQLiteDatabase) -> T): T = runInTransaction<T> { block(openHelper.writableDatabase) }

    /** Read access for infrastructure adapters; callers cannot open an alternate SQLite connection. */
    fun <T> readLedger(block: (SupportSQLiteDatabase) -> T): T = block(openHelper.readableDatabase)
}

@Database(
    entities = [StagingSchemaRegistryEntity::class],
    version = LedgerSchemaDefinition.STAGING_VERSION,
    exportSchema = true,
)
abstract class ImportStagingDatabase : RoomDatabase() {
    internal abstract fun schemaRegistryDao(): StagingSchemaRegistryDao

    /** Staging is an isolated operation database; every append batch has its own SQLCipher transaction. */
    fun <T> inStagingTransaction(block: (SupportSQLiteDatabase) -> T): T = runInTransaction<T> { block(openHelper.writableDatabase) }

    fun <T> readStaging(block: (SupportSQLiteDatabase) -> T): T = block(openHelper.readableDatabase)
}

object EncryptedDatabaseFactory {
    const val PRIMARY_DATABASE_NAME: String = "ledger.db"

    fun openPrimary(
        context: Context,
        passphrase: ByteArray,
    ): LedgerDatabase = openPrimaryNamed(context, passphrase, PRIMARY_DATABASE_NAME)

    fun openLedgerCopy(
        context: Context,
        operationFileName: String,
        passphrase: ByteArray,
    ): LedgerDatabase {
        require(LEDGER_COPY_NAME.matches(operationFileName)) { "invalid opaque ledger copy name" }
        return openPrimaryNamed(context, passphrase, operationFileName)
    }

    private fun openPrimaryNamed(context: Context, passphrase: ByteArray, name: String): LedgerDatabase {
        validatePassphrase(passphrase)
        SqlCipherNativeLibrary.ensureLoaded()
        return Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf(), SecureSqlCipherHook, true))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .apply { LedgerMigrations.registered(context.applicationContext).forEach { migration -> addMigrations(migration) } }
            .addCallback(PrimaryDatabaseCallback(context.applicationContext))
            .build()
    }

    fun openImportStaging(
        context: Context,
        operationFileName: String,
        passphrase: ByteArray,
    ): ImportStagingDatabase {
        require(STAGING_NAME.matches(operationFileName)) { "invalid opaque staging database name" }
        validatePassphrase(passphrase)
        SqlCipherNativeLibrary.ensureLoaded()
        return Room.databaseBuilder(context.applicationContext, ImportStagingDatabase::class.java, operationFileName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf(), SecureSqlCipherHook, true))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .apply { StagingMigrations.registered.forEach { migration -> addMigrations(migration) } }
            .addCallback(StagingDatabaseCallback(context.applicationContext))
            .build()
    }

    private fun validatePassphrase(passphrase: ByteArray) {
        require(passphrase.size >= MINIMUM_PASSPHRASE_BYTES) { "SQLCipher passphrase material is too short" }
        require(passphrase.any { it != 0.toByte() }) { "SQLCipher passphrase material cannot be all zero" }
    }

    private val STAGING_NAME = Regex("import_[0-9a-f]{32}\\.db")
    private val LEDGER_COPY_NAME = Regex("ledger_(shadow|safety)_[0-9a-f]{32}\\.db")
    private const val MINIMUM_PASSPHRASE_BYTES = 32
}

internal object SqlCipherNativeLibrary {
    @Volatile private var loaded: Boolean = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}

private abstract class SecureDatabaseCallback : RoomDatabase.Callback() {
    final override fun onOpen(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        require(singleLong(db, "PRAGMA foreign_keys") == 1L) { "foreign_keys must remain enabled" }
        require(singleLong(db, "PRAGMA temp_store") == 2L) { "temporary SQLite data must remain in memory" }
        require(singleLong(db, "PRAGMA auto_vacuum") == 2L) { "incremental auto-vacuum must remain enabled" }
    }

    private fun singleLong(database: SupportSQLiteDatabase, sql: String): Long = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "pragma returned no row" }
        cursor.getLong(0)
    }
}

internal object SecureSqlCipherHook : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) = Unit

    override fun postKey(connection: SQLiteConnection) {
        SECURE_PRAGMAS.forEach { pragma -> connection.executeRaw(pragma, emptyArray(), null) }
    }

    private val SECURE_PRAGMAS = listOf(
        "PRAGMA auto_vacuum = INCREMENTAL",
        "PRAGMA foreign_keys = ON",
        "PRAGMA temp_store = MEMORY",
        "PRAGMA secure_delete = ON",
        "PRAGMA recursive_triggers = ON",
        "PRAGMA cipher_memory_security = ON",
        "PRAGMA cipher_log = NONE",
    )
}

private class PrimaryDatabaseCallback(private val context: Context) : SecureDatabaseCallback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        LedgerSchemaDefinition.installPrimary(context, db)
    }
}

private class StagingDatabaseCallback(private val context: Context) : SecureDatabaseCallback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        LedgerSchemaDefinition.installStaging(context, db)
    }
}
