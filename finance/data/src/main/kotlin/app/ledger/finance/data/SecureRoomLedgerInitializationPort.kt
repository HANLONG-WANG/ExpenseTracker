@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.EmptyLedgerState
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerInitializationError
import app.ledger.finance.application.LedgerInitializationPort
import app.ledger.finance.application.UpdateBookLocaleCommand
import app.ledger.finance.domain.BookState
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.EntityChangeOperation
import app.ledger.finance.domain.EntityRevisionAction
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.LedgerOwnerType
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import java.security.MessageDigest
import java.time.Instant

/** Creates the real encrypted empty book and optional initial reference data; it never creates examples or financial facts. */
public class SecureRoomLedgerInitializationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : LedgerInitializationPort {
    private val applicationContext = context.applicationContext
    private val projectionEngine = RoomProjectionEngine()

    override suspend fun initialize(command: InitializeLedgerCommand): DomainResult<Unit> {
        keyProvider.initialize(command.ids.bookId)
        return withDatabase(command.ids.bookId) { database ->
            database.inLedgerTransaction { connection ->
                val existing = connection.queryOne("SELECT uid FROM book WHERE id = 1") { cursor -> cursor.getBlob(0) }
                if (existing != null) {
                    if (!existing.contentEquals(command.ids.bookId.bytes)) {
                        abort(LedgerInitializationError.AlreadyInitializedWithDifferentBook)
                    }
                    return@inLedgerTransaction
                }
                insertGenesis(connection, command)
            }
        }
    }

    override suspend fun updateBookLocale(
        bookId: StableId,
        command: UpdateBookLocaleCommand,
    ): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            val book = requireBook(connection, bookId)
            if (book.firstFinancialCommitAt != null) abort(LedgerInitializationError.BaseCurrencyLocked)
            if (book.baseCurrency == command.baseCurrency.value && book.zoneId == command.defaultZoneId.id) {
                return@inLedgerTransaction
            }
            val nextRevision = Math.addExact(book.localRevision, 1L)
            val snapshot = canonical(
                "book",
                bookId.toString(),
                command.baseCurrency.value,
                command.defaultZoneId.id,
                nextRevision.toString(),
            )
            insertReferenceCommit(
                connection,
                command.commitId,
                command.deviceInstanceId,
                command.changedAt,
                nextRevision,
                book.headCommitId,
                snapshot,
            )
            connection.execSQL(
                "UPDATE book SET base_currency = ?, default_zone_id = ?, head_commit_id = ?, local_revision = ?, valuation_revision = ? WHERE id = 1",
                arrayOf<Any>(
                    command.baseCurrency.value,
                    command.defaultZoneId.id,
                    connection.requireInternalId("book_commit", command.commitId),
                    nextRevision,
                    nextRevision,
                ),
            )
            connection.execSQL(
                "UPDATE ledger_account SET currency_code = ? WHERE owner_type = ?",
                arrayOf<Any>(command.baseCurrency.value, LedgerOwnerType.SYSTEM.ordinal),
            )
            insertEntityRevision(
                connection,
                command.revisionId,
                EntityType.BOOK,
                bookId,
                nextRevision.toInt(),
                EntityRevisionAction.EDIT,
                command.commitId,
                snapshot,
            )
            insertEntityChange(
                connection,
                command.commitId,
                EntityType.BOOK,
                bookId,
                EntityChangeOperation.UPDATE,
                command.revisionId,
                snapshot,
            )
            projectionEngine.rebuildAll(connection, nextRevision, nextRevision)
        }
    }

    override suspend fun createFirstAccount(bookId: StableId, command: InitialAccountCommand): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            val book = requireBook(connection, bookId)
            if (count(connection, "SELECT COUNT(*) FROM user_account") != 0L) abort(LedgerInitializationError.DuplicateReference)
            val nextRevision = Math.addExact(book.localRevision, 1L)
            val snapshot = canonical(
                "account",
                command.accountId.toString(),
                command.type.name,
                command.name,
                command.currency.value,
                command.iconKey,
                command.colorArgb.toString(),
            )
            insertReferenceCommit(
                connection,
                command.commitId,
                command.deviceInstanceId,
                command.createdAt,
                nextRevision,
                book.headCommitId,
                snapshot,
            )
            val ledgerId = connection.allocateInternalId("ledger_account", command.ledgerAccountId)
            connection.execSQL(
                "INSERT INTO ledger_account(id, uid, owner_type, account_class, normal_side, currency_code, parent_ledger_account_id, system_code, status, created_commit_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)",
                arrayOf<Any>(
                    ledgerId,
                    command.ledgerAccountId.bytes,
                    LedgerOwnerType.USER_ACCOUNT.ordinal,
                    LedgerAccountClass.ASSET.ordinal,
                    DebitCredit.DEBIT.ordinal,
                    command.currency.value,
                    EntityStatus.ACTIVE.ordinal,
                    connection.requireInternalId("book_commit", command.commitId),
                ),
            )
            connection.execSQL(
                "INSERT INTO user_account(id, uid, ledger_account_id, type, name, currency_code, institution_name, branch_name, account_number, opened_date, " +
                    "status, icon_key, color_argb, sort_order, last_commit_id, row_version, content_hash) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, 0, ?, 1, ?)",
                arrayOf<Any>(
                    connection.allocateInternalId("user_account", command.accountId),
                    command.accountId.bytes,
                    ledgerId,
                    command.type.ordinal,
                    command.name,
                    command.currency.value,
                    EntityStatus.ACTIVE.ordinal,
                    command.iconKey,
                    command.colorArgb,
                    connection.requireInternalId("book_commit", command.commitId),
                    sha256(snapshot),
                ),
            )
            insertEntityRevision(connection, command.revisionId, EntityType.ACCOUNT, command.accountId, 1, EntityRevisionAction.CREATE, command.commitId, snapshot)
            insertEntityChange(connection, command.commitId, EntityType.ACCOUNT, command.accountId, EntityChangeOperation.CREATE, command.revisionId, snapshot)
            advanceBookAndProjections(connection, command.commitId, nextRevision)
        }
    }

    override suspend fun createFirstCategory(bookId: StableId, command: InitialCategoryCommand): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            val book = requireBook(connection, bookId)
            if (count(connection, "SELECT COUNT(*) FROM category") != 0L) abort(LedgerInitializationError.DuplicateReference)
            val nextRevision = Math.addExact(book.localRevision, 1L)
            val snapshot = canonical(
                "category",
                command.categoryId.toString(),
                command.direction.name,
                command.name,
                command.normalizedName,
                command.statisticalNature.name,
                command.iconKey,
                command.colorArgb.toString(),
            )
            insertReferenceCommit(
                connection,
                command.commitId,
                command.deviceInstanceId,
                command.createdAt,
                nextRevision,
                book.headCommitId,
                snapshot,
            )
            connection.execSQL(
                "INSERT INTO category(id, uid, direction, parent_id, depth, name, normalized_name, icon_key, color_argb, sort_order, status, " +
                    "statistical_nature, default_account_id, default_card_id, default_merchant_id, last_commit_id, row_version) " +
                    "VALUES (?, ?, ?, NULL, 1, ?, ?, ?, ?, 0, ?, ?, NULL, NULL, NULL, ?, 1)",
                arrayOf<Any>(
                    connection.allocateInternalId("category", command.categoryId),
                    command.categoryId.bytes,
                    command.direction.ordinal,
                    command.name,
                    command.normalizedName,
                    command.iconKey,
                    command.colorArgb,
                    CategoryStatus.ACTIVE.ordinal,
                    command.statisticalNature.ordinal,
                    connection.requireInternalId("book_commit", command.commitId),
                ),
            )
            insertEntityRevision(connection, command.revisionId, EntityType.CATEGORY, command.categoryId, 1, EntityRevisionAction.CREATE, command.commitId, snapshot)
            insertEntityChange(connection, command.commitId, EntityType.CATEGORY, command.categoryId, EntityChangeOperation.CREATE, command.revisionId, snapshot)
            advanceBookAndProjections(connection, command.commitId, nextRevision)
        }
    }

    override suspend fun emptyLedgerState(bookId: StableId): DomainResult<EmptyLedgerState> = withDatabase(bookId) { database ->
        database.readLedger { connection ->
            requireBook(connection, bookId)
            EmptyLedgerState(
                hasUserAccount = count(connection, "SELECT COUNT(*) FROM user_account") > 0,
                hasCategory = count(connection, "SELECT COUNT(*) FROM category") > 0,
                hasTransaction = count(connection, "SELECT COUNT(*) FROM business_transaction") > 0,
            )
        }
    }

    override suspend fun clearLocalBook(bookId: StableId): DomainResult<Unit> = try {
        val databaseFile = applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        check(!databaseFile.exists() || applicationContext.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME))
        keyProvider.destroyLocal(bookId)
        DomainResult.Success(Unit)
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        DomainResult.Failure(LedgerInitializationError.ClearLocalBookFailed)
    }

    private fun insertGenesis(connection: SupportSQLiteDatabase, command: InitializeLedgerCommand) {
        val ids = command.ids
        val createdAt = command.createdAt.toEpochMilli()
        val snapshot = canonical("book", ids.bookId.toString(), command.baseCurrency.value, command.defaultZoneId.id, "1")
        connection.execSQL(
            "INSERT INTO rule_set_version(version, algorithm_hash, activated_at, retired_at) VALUES (1, ?, ?, NULL)",
            arrayOf<Any>(sha256(RULE_SET_CANONICAL.toByteArray()), createdAt),
        )
        connection.execSQL(
            "INSERT INTO book_commit(id, uid, local_revision, kind, command_uid, device_instance_uid, created_at, root_hash) VALUES (?, ?, 1, ?, NULL, ?, ?, ?)",
            arrayOf<Any>(
                ids.commitId.internalId(),
                ids.commitId.bytes,
                CommitKind.REFERENCE_DATA_CHANGE.ordinal,
                ids.deviceInstanceId.bytes,
                createdAt,
                sha256(snapshot),
            ),
        )
        connection.execSQL(
            "INSERT INTO book(id, uid, base_currency, default_zone_id, head_commit_id, local_revision, valuation_revision, rule_set_version, created_at, first_financial_commit_at, state) " +
                "VALUES (1, ?, ?, ?, ?, 1, 1, 1, ?, NULL, ?)",
            arrayOf<Any>(ids.bookId.bytes, command.baseCurrency.value, command.defaultZoneId.id, ids.commitId.internalId(), createdAt, BookState.READY.ordinal),
        )
        insertEntityRevision(connection, ids.bookRevisionId, EntityType.BOOK, ids.bookId, 1, EntityRevisionAction.CREATE, ids.commitId, snapshot)
        insertEntityChange(connection, ids.commitId, EntityType.BOOK, ids.bookId, EntityChangeOperation.CREATE, ids.bookRevisionId, snapshot)
        SystemLedgerCode.entries.forEach { code ->
            val ledgerId = ids.systemLedgerIds.getValue(code)
            val accountClass = accountClass(code)
            connection.execSQL(
                "INSERT INTO ledger_account(id, uid, owner_type, account_class, normal_side, currency_code, parent_ledger_account_id, system_code, status, created_commit_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)",
                arrayOf<Any>(
                    connection.allocateInternalId("ledger_account", ledgerId),
                    ledgerId.bytes,
                    LedgerOwnerType.SYSTEM.ordinal,
                    accountClass.ordinal,
                    normalSide(accountClass).ordinal,
                    command.baseCurrency.value,
                    code.name,
                    EntityStatus.ACTIVE.ordinal,
                    ids.commitId.internalId(),
                ),
            )
        }
        projectionEngine.rebuildAll(connection, 1L, 1L)
    }

    private fun insertReferenceCommit(
        connection: SupportSQLiteDatabase,
        commitId: StableId,
        deviceInstanceId: StableId,
        changedAt: Instant,
        localRevision: Long,
        parentCommitId: Long,
        snapshot: ByteArray,
    ) {
        connection.execSQL(
            "INSERT INTO book_commit(id, uid, local_revision, kind, command_uid, device_instance_uid, created_at, root_hash) VALUES (?, ?, ?, ?, NULL, ?, ?, ?)",
            arrayOf<Any>(
                connection.allocateInternalId("book_commit", commitId),
                commitId.bytes,
                localRevision,
                CommitKind.REFERENCE_DATA_CHANGE.ordinal,
                deviceInstanceId.bytes,
                changedAt.toEpochMilli(),
                sha256(snapshot),
            ),
        )
        connection.execSQL(
            "INSERT INTO book_commit_parent(commit_id, parent_commit_id, ordinal) VALUES (?, ?, 0)",
            arrayOf<Any>(connection.requireInternalId("book_commit", commitId), parentCommitId),
        )
    }

    private fun insertEntityRevision(
        connection: SupportSQLiteDatabase,
        revisionId: StableId,
        entityType: EntityType,
        entityId: StableId,
        revisionNumber: Int,
        action: EntityRevisionAction,
        commitId: StableId,
        snapshot: ByteArray,
    ) {
        connection.execSQL(
            "INSERT INTO entity_revision(id, uid, entity_type, entity_uid, revision_no, action, commit_id, content_hash, canonical_snapshot_blob, schema_version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
            arrayOf<Any>(
                connection.allocateInternalId("entity_revision", revisionId),
                revisionId.bytes,
                entityType.ordinal,
                entityId.bytes,
                revisionNumber,
                action.ordinal,
                connection.requireInternalId("book_commit", commitId),
                sha256(snapshot),
                snapshot,
            ),
        )
    }

    private fun insertEntityChange(
        connection: SupportSQLiteDatabase,
        commitId: StableId,
        entityType: EntityType,
        entityId: StableId,
        operation: EntityChangeOperation,
        revisionId: StableId,
        snapshot: ByteArray,
    ) {
        connection.execSQL(
            "INSERT INTO entity_change(commit_id, entity_type, entity_uid, operation, before_hash, after_hash, entity_revision_uid) " +
                "VALUES (?, ?, ?, ?, NULL, ?, ?)",
            arrayOf<Any>(connection.requireInternalId("book_commit", commitId), entityType.ordinal, entityId.bytes, operation.ordinal, sha256(snapshot), revisionId.bytes),
        )
    }

    private fun advanceBookAndProjections(connection: SupportSQLiteDatabase, commitId: StableId, revision: Long) {
        connection.execSQL(
            "UPDATE book SET head_commit_id = ?, local_revision = ?, valuation_revision = ? WHERE id = 1",
            arrayOf<Any>(connection.requireInternalId("book_commit", commitId), revision, revision),
        )
        projectionEngine.rebuildAll(connection, revision, revision)
    }

    private fun requireBook(connection: SupportSQLiteDatabase, bookId: StableId): BookRow {
        val book = connection.queryOne(
            "SELECT uid, base_currency, default_zone_id, head_commit_id, local_revision, first_financial_commit_at FROM book WHERE id = 1",
        ) { cursor ->
            BookRow(
                uid = cursor.getBlob(0),
                baseCurrency = cursor.getString(1),
                zoneId = cursor.getString(2),
                headCommitId = cursor.getLong(3),
                localRevision = cursor.getLong(4),
                firstFinancialCommitAt = if (cursor.isNull(5)) null else cursor.getLong(5),
            )
        } ?: abort(LedgerInitializationError.BookNotInitialized)
        if (!book.uid.contentEquals(bookId.bytes)) abort(LedgerInitializationError.AlreadyInitializedWithDifferentBook)
        return book
    }

    private inline fun <T> withDatabase(bookId: StableId, block: (LedgerDatabase) -> T): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { passphrase ->
                EncryptedDatabaseFactory.openPrimary(applicationContext, passphrase)
            }
            try {
                DomainResult.Success(block(database))
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: Exception) {
        DomainResult.Failure(LedgerInitializationError.InvalidReference)
    }

    private fun count(connection: SupportSQLiteDatabase, sql: String): Long = connection.queryOne(sql) { cursor -> cursor.getLong(0) } ?: 0L

    private fun canonical(vararg values: String): ByteArray = values.joinToString(separator = "\u001f").toByteArray(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun accountClass(code: SystemLedgerCode): LedgerAccountClass = when (code) {
        SystemLedgerCode.SYSTEM_INCOME_REGULAR,
        SystemLedgerCode.SYSTEM_INCOME_NON_RECURRING,
        SystemLedgerCode.SYSTEM_FX_GAIN,
        -> LedgerAccountClass.INCOME
        SystemLedgerCode.SYSTEM_EXPENSE_CONSUMPTION,
        SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION,
        SystemLedgerCode.SYSTEM_FX_COST,
        -> LedgerAccountClass.EXPENSE
        SystemLedgerCode.SYSTEM_OPENING_EQUITY,
        SystemLedgerCode.SYSTEM_BALANCE_ADJUSTMENT,
        -> LedgerAccountClass.EQUITY
        SystemLedgerCode.SYSTEM_FX_CLEARING,
        SystemLedgerCode.SYSTEM_FX_ROUNDING,
        -> LedgerAccountClass.CLEARING
    }

    private fun normalSide(accountClass: LedgerAccountClass): DebitCredit = when (accountClass) {
        LedgerAccountClass.INCOME, LedgerAccountClass.EQUITY, LedgerAccountClass.LIABILITY -> DebitCredit.CREDIT
        else -> DebitCredit.DEBIT
    }

    private data class BookRow(
        val uid: ByteArray,
        val baseCurrency: String,
        val zoneId: String,
        val headCommitId: Long,
        val localRevision: Long,
        val firstFinancialCommitAt: Long?,
    )

    private companion object {
        const val RULE_SET_CANONICAL = "ledger-accounting-rules-v1"
    }
}
