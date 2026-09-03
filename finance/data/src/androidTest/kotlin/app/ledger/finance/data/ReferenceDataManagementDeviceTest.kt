@file:Suppress("LongMethod", "LongParameterList", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.CardDraft
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.MerchantDraft
import app.ledger.finance.application.OpeningBalanceWriteIds
import app.ledger.finance.application.OpeningBalanceWriteRequest
import app.ledger.finance.application.PlaceDraft
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountSnapshot
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.LedgerAccountId
import app.ledger.finance.domain.LedgerAccountSnapshot
import app.ledger.finance.domain.LocationRecordId
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.PlanningAccount
import app.ledger.finance.domain.PlanningCategory
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PlanningSystemLedger
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.RecordExpenseCommand
import app.ledger.finance.domain.ReferenceDataViolation
import app.ledger.finance.domain.RowVersion
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReferenceDataManagementDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var initialization: SecureRoomLedgerInitializationPort
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var openingBalances: SecureRoomOpeningBalanceWritePort
    private var revision: Long = 1
    private var seed: Long = 1_000

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        databaseAccess = DeviceTestLedgerDatabaseAccess(context, keys)
        initialization = SecureRoomLedgerInitializationPort(context, keys)
        references = SecureRoomReferenceDataManagementPort(databaseAccess)
        openingBalances = SecureRoomOpeningBalanceWritePort(databaseAccess)
        runBlocking {
            initialization.initialize(
                InitializeLedgerCommand(
                    LedgerGenesisIds(
                        BOOK_ID,
                        id(2),
                        id(3),
                        id(4),
                        SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                    ),
                    currency("JPY"),
                    ZoneId.of("Asia/Tokyo"),
                    Instant.ofEpochMilli(1_000),
                ),
            ).success()
        }
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun accountCardCategoryMerchantPlaceAndCheckpointRulesAreAtomicAndAudited() = runBlocking {
        val cash = createAccount(UserAccountType.CASH, "Cash", currency("JPY"))
        val bank = createAccount(UserAccountType.BANK, "Bank", currency("JPY"))
        val credit = createAccount(UserAccountType.CREDIT, "Credit", currency("JPY"))
        val loan = createAccount(UserAccountType.LOAN, "Loan", currency("JPY"))
        assertEquals(UserAccountType.entries.toSet(), snapshot().accounts.map { it.type }.toSet())

        val incompatible = mutateResult(
            ReferenceMutation.SaveCard(cardDraft(nextId(), cash, CardType.DEBIT, "Invalid")),
        )
        assertEquals(DomainResult.Failure(ReferenceDataViolation.CardAccountIncompatible), incompatible)
        val debit = nextId()
        mutate(ReferenceMutation.SaveCard(cardDraft(debit, bank, CardType.DEBIT, "Debit")))
        val primary = nextId()
        mutate(ReferenceMutation.SaveCard(cardDraft(primary, credit, CardType.CREDIT_PRIMARY, "Primary")))
        val replacement = nextId()
        val old = snapshot().cards.single { it.id == primary }
        mutate(
            ReferenceMutation.ReplaceCard(
                old.id,
                old.rowVersion,
                cardDraft(replacement, credit, CardType.CREDIT_PRIMARY, "Replacement", primary),
            ),
        )
        assertEquals(EntityStatus.ARCHIVED, snapshot().cards.single { it.id == primary }.status)
        assertEquals(primary, snapshot().cards.single { it.id == replacement }.replacementOfId)

        val openingRevision = revision
        val openingSeed = seed.also { seed += 400 }
        openingBalances.record(
            OpeningBalanceWriteRequest(
                ids = OpeningBalanceWriteIds(
                    BOOK_ID,
                    CommandId(id(openingSeed)),
                    id(openingSeed + 1),
                    id(openingSeed + 2),
                    id(openingSeed + 3),
                    id(openingSeed + 4),
                    id(openingSeed + 5),
                    (openingSeed + 10..openingSeed + 300).map(::id),
                ),
                accountId = cash,
                balanceDate = LocalDate.of(2026, 8, 1),
                accountMinor = 12_345,
                baseMinor = null,
                createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            ),
        ).success()
        revision = openingRevision + 1
        val afterOpening = snapshot()
        assertEquals(12_345, afterOpening.accounts.single { it.id == cash }.balanceMinor)
        assertEquals(12_345, afterOpening.accountTransactions.single { it.accountId == cash }.runningBalanceMinor)
        val locked = afterOpening.accounts.single { it.id == cash }
        assertEquals(
            DomainResult.Failure(ReferenceDataViolation.CurrencyLocked),
            mutateResult(
                ReferenceMutation.SaveAccount(
                    accountDraft(cash, nextId(), UserAccountType.CASH, "Cash", currency("USD"), locked.rowVersion),
                ),
            ),
        )

        val checkpointId = nextId()
        mutate(
            ReferenceMutation.SaveCheckpoint(
                checkpointId,
                cash,
                Instant.parse("2026-08-02T00:00:00Z"),
                LocalDate.of(2026, 8, 2),
                12_000,
                "counted",
            ),
        )
        val afterCheckpoint = snapshot()
        assertEquals(12_345, afterCheckpoint.accounts.single { it.id == cash }.balanceMinor)
        assertEquals(-345, afterCheckpoint.checkpoints.single { it.id == checkpointId }.differenceMinor)

        val parent = nextId()
        mutate(ReferenceMutation.SaveCategory(categoryDraft(parent, CategoryDirection.EXPENSE, null, "Food")))
        val child = nextId()
        mutate(ReferenceMutation.SaveCategory(categoryDraft(child, CategoryDirection.EXPENSE, parent, "Lunch")))
        val childRow = snapshot().categories.single { it.id == child }
        val otherParent = nextId()
        mutate(ReferenceMutation.SaveCategory(categoryDraft(otherParent, CategoryDirection.EXPENSE, null, "Other")))
        assertEquals(
            DomainResult.Failure(ReferenceDataViolation.CategoryParentLocked),
            mutateResult(ReferenceMutation.SaveCategory(categoryDraft(child, CategoryDirection.EXPENSE, otherParent, "Lunch", childRow.rowVersion))),
        )
        mutate(ReferenceMutation.RemoveCategory(child, childRow.rowVersion, CategoryRemovalStrategy.TOMBSTONE, null))
        assertEquals(app.ledger.finance.domain.CategoryStatus.DELETED_TOMBSTONE, snapshot().categories.single { it.id == child }.status)

        val merchantA = nextId()
        val merchantB = nextId()
        mutate(ReferenceMutation.SaveMerchant(MerchantDraft(merchantA, null, "Cafe A", "cafe a", setOf("A"))))
        mutate(ReferenceMutation.SaveMerchant(MerchantDraft(merchantB, null, "Cafe B", "cafe b", setOf("B"))))
        val place = nextId()
        mutate(ReferenceMutation.SavePlace(PlaceDraft(place, null, "Station", 356_000_000, 1_397_000_000, merchantA)))
        val defaultedCategory = nextId()
        mutate(
            ReferenceMutation.SaveCategory(
                categoryDraft(
                    defaultedCategory,
                    CategoryDirection.EXPENSE,
                    null,
                    "Commute",
                    defaultAccountId = bank,
                    defaultCardId = debit,
                    defaultMerchantId = merchantA,
                ),
            ),
        )
        val defaulted = snapshot().categories.single { it.id == defaultedCategory }
        assertEquals(bank, defaulted.defaultAccountId)
        assertEquals(debit, defaulted.defaultCardId)
        assertEquals(merchantA, defaulted.defaultMerchantId)
        assertEquals(
            DomainResult.Failure(ReferenceDataViolation.CardAccountIncompatible),
            mutateResult(
                ReferenceMutation.SaveCategory(
                    categoryDraft(nextId(), CategoryDirection.EXPENSE, null, "Invalid defaults", defaultAccountId = credit, defaultCardId = debit),
                ),
            ),
        )

        val splitRecord = seedLocationRecord(place)
        recordExpense(bank, defaultedCategory, splitRecord)
        val splitPlace = nextId()
        val replacementRecord = nextId()
        mutate(
            ReferenceMutation.SplitPlace(
                place,
                PlaceDraft(splitPlace, null, "Station east", 356_000_100, 1_397_000_100, merchantA),
                listOf(splitRecord),
                listOf(replacementRecord),
            ),
        )
        val afterSplit = snapshot()
        assertTrue(afterSplit.places.any { it.id == splitPlace })
        assertEquals(1L, afterSplit.locations.single { it.id == replacementRecord }.currentTransactionCount)
        assertEquals(0L, afterSplit.locations.single { it.id == splitRecord }.currentTransactionCount)
        mutate(ReferenceMutation.RemoveCategory(defaultedCategory, defaulted.rowVersion, CategoryRemovalStrategy.REASSIGN, parent))
        assertEquals(CategoryStatus.DELETED_TOMBSTONE, snapshot().categories.single { it.id == defaultedCategory }.status)
        mutate(ReferenceMutation.MergeMerchant(merchantA, merchantB))
        val mergedSnapshot = snapshot()
        assertEquals(merchantB, mergedSnapshot.places.single { it.id == place }.merchantId)
        assertEquals(merchantB, mergedSnapshot.merchants.single { it.id == merchantA }.mergedIntoId)

        val disposable = createAccount(UserAccountType.BANK, "Disposable", currency("JPY"))
        val disposableRow = snapshot().accounts.single { it.id == disposable }
        mutate(ReferenceMutation.DeleteEmptyAccount(disposable, disposableRow.rowVersion))
        assertFalse(snapshot().accounts.any { it.id == disposable })
        val used = snapshot().accounts.single { it.id == cash }
        assertEquals(
            DomainResult.Failure(ReferenceDataViolation.AccountHasHistory),
            mutateResult(ReferenceMutation.DeleteEmptyAccount(cash, used.rowVersion)),
        )
        mutate(ReferenceMutation.ArchiveAccount(cash, used.rowVersion))
        assertEquals(EntityStatus.ARCHIVED, snapshot().accounts.single { it.id == cash }.status)
        assertTrue(snapshot().localRevision == revision)
        assertTrue(loan != credit && debit != replacement)

        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.readLedger { db ->
                    assertEquals(revision, scalar(db, "SELECT local_revision FROM book"))
                    assertEquals(revision, scalar(db, "SELECT COUNT(*) FROM book_commit"))
                    assertTrue(scalar(db, "SELECT COUNT(*) FROM entity_revision") >= revision - 1)
                    assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM book_commit WHERE kind=${app.ledger.finance.domain.CommitKind.BATCH_MUTATION.ordinal}"))
                    assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id WHERE bt.kind=${app.ledger.finance.domain.TransactionKind.EXPENSE.ordinal}"))
                    assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM journal_entry WHERE entry_role=${app.ledger.finance.domain.JournalEntryRole.REVERSE.ordinal}"))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM (SELECT reverse.reverses_entry_id FROM journal_entry reverse JOIN journal_entry apply ON apply.id=reverse.reverses_entry_id GROUP BY reverse.reverses_entry_id HAVING COUNT(*)<>1)"))
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id JOIN category c ON c.id=tr.category_id WHERE bt.kind=${app.ledger.finance.domain.TransactionKind.EXPENSE.ordinal} AND c.uid=?", parent.bytes))
                    assertEquals(
                        "ok",
                        db.query("PRAGMA integrity_check").use { cursor ->
                            cursor.moveToFirst()
                            cursor.getString(0)
                        },
                    )
                    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM account_balance_checkpoint cp JOIN journal_entry je ON je.source_revision_id=cp.id"))
                }
            } finally {
                database.close()
            }
        }
    }

    private suspend fun createAccount(type: UserAccountType, name: String, currency: CurrencyCode): StableId {
        val account = nextId()
        mutate(ReferenceMutation.SaveAccount(accountDraft(account, nextId(), type, name, currency, null)))
        return account
    }

    private fun accountDraft(
        id: StableId,
        ledger: StableId,
        type: UserAccountType,
        name: String,
        currency: CurrencyCode,
        rowVersion: Long?,
    ) = AccountDraft(id, ledger, rowVersion, type, name, currency, null, null, null, null, "account", 0xff006c4c.toInt(), 0)

    private fun cardDraft(
        id: StableId,
        account: StableId,
        type: CardType,
        name: String,
        replacement: StableId? = null,
    ) = CardDraft(id, null, account, type, name, "1234", replacement, "card", 0xff006c4c.toInt(), 0)

    private fun categoryDraft(
        id: StableId,
        direction: CategoryDirection,
        parent: StableId?,
        name: String,
        rowVersion: Long? = null,
        defaultAccountId: StableId? = null,
        defaultCardId: StableId? = null,
        defaultMerchantId: StableId? = null,
    ) = CategoryDraft(
        id,
        rowVersion,
        direction,
        parent,
        name,
        name.lowercase(),
        "category",
        0xff006c4c.toInt(),
        0,
        if (direction == CategoryDirection.EXPENSE) StatisticalNature.CONSUMPTION_EXPENSE else StatisticalNature.REGULAR_INCOME,
        defaultAccountId,
        defaultCardId,
        defaultMerchantId,
    )

    private fun seedLocationRecord(placeId: StableId): StableId {
        val locationId = nextId()
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.inLedgerTransaction { db ->
                    db.execSQL(
                        "INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,place_id,created_commit_id) " +
                            "VALUES ((SELECT COALESCE(MAX(id),0)+1 FROM location_record),?,?,?,?,?,?,?," +
                            "(SELECT id FROM place WHERE uid=?),(SELECT head_commit_id FROM book WHERE id=1))",
                        arrayOf<Any?>(locationId.bytes, 356_000_000, 1_397_000_000, null, 1_659_312_000_000, 1, "USER", placeId.bytes),
                    )
                }
            } finally {
                database.close()
            }
        }
        return locationId
    }

    private suspend fun recordExpense(accountId: StableId, categoryId: StableId, locationId: StableId) {
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                val snapshot = database.readLedger { db ->
                    val book = RoomBookRepository.mapCurrent(db)
                    val account = db.queryOne(
                        "SELECT ua.uid account_uid,la.uid ledger_uid,ua.type,ua.currency_code,ua.status,ua.row_version,la.account_class,la.normal_side,la.status ledger_status FROM user_account ua JOIN ledger_account la ON la.id=ua.ledger_account_id WHERE ua.uid=?",
                        arrayOf(accountId.bytes),
                    ) { cursor ->
                        val moneyCurrency = currency(cursor.getString(cursor.getColumnIndexOrThrow("currency_code")))
                        val ledger = LedgerAccountSnapshot(LedgerAccountId(cursor.stableId("ledger_uid")), LedgerAccountClass.entries[cursor.getInt(cursor.getColumnIndexOrThrow("account_class"))], DebitCredit.entries[cursor.getInt(cursor.getColumnIndexOrThrow("normal_side"))], moneyCurrency, EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("ledger_status"))])
                        PlanningAccount(AccountSnapshot(UserAccountId(cursor.stableId("account_uid")), ledger.id, UserAccountType.entries[cursor.getInt(cursor.getColumnIndexOrThrow("type"))], moneyCurrency, EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))], RowVersion.of(cursor.getLong(cursor.getColumnIndexOrThrow("row_version"))).success(), false), ledger)
                    } ?: error("account missing")
                    val category = db.queryOne("SELECT c.uid,parent.uid parent_uid,c.direction,c.statistical_nature,c.status FROM category c LEFT JOIN category parent ON parent.id=c.parent_id WHERE c.uid=?", arrayOf(categoryId.bytes)) { cursor ->
                        val idValue = CategoryId(cursor.stableId("uid"))
                        PlanningCategory(idValue, cursor.nullableStableId("parent_uid")?.let(::CategoryId) ?: idValue, CategoryDirection.entries[cursor.getInt(cursor.getColumnIndexOrThrow("direction"))], StatisticalNature.entries[cursor.getInt(cursor.getColumnIndexOrThrow("statistical_nature"))], CategoryStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))])
                    } ?: error("category missing")
                    val systems = db.queryList("SELECT uid,account_class,normal_side,currency_code,status,system_code FROM ledger_account WHERE owner_type=2") { cursor ->
                        PlanningSystemLedger(SystemLedgerCode.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("system_code"))), LedgerAccountSnapshot(LedgerAccountId(cursor.stableId("uid")), LedgerAccountClass.entries[cursor.getInt(cursor.getColumnIndexOrThrow("account_class"))], DebitCredit.entries[cursor.getInt(cursor.getColumnIndexOrThrow("normal_side"))], currency(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))), EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))]))
                    }
                    val references = PlanningReferenceData(listOf(account), emptyList(), listOf(category), emptyList(), emptyList(), systems, emptyList(), emptyList())
                    val positive = PositiveMoney.from(Money(1_000, account.account.currency)).success()
                    val accountAmount = AccountAmount.create(account.account, positive.money).success()
                    val input = NewTransactionInput(
                        TransactionContextInput(EffectiveTime.fromInstant(Instant.parse("2026-08-02T03:00:00Z"), ZoneId.of("Asia/Tokyo")), LocalDate.of(2026, 8, 2), YearMonth.of(2026, 8), null, null, null, LocationRecordId(locationId), "reference batch", "1000", TransactionSource.MANUAL, null, null, emptyList()),
                        ExpensePayload(CategoryAssignment(category.id, category.direction, category.statisticalNature), ExpensePayer.LocalAccount(accountAmount, null), positive, null, emptyList(), null),
                    )
                    val commandSeed = nextId()
                    val draft = RecordExpenseCommand(CommandId(commandSeed), Hash256.fromBytes(ByteArray(32)).success(), input)
                    val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
                    val evidence = FrozenAmountEvidence.create(AmountEvidenceKey(AmountRole.PRIMARY, 0), positive, positive, positive, account.account.id, null, null).success()
                    val transactionId = nextId()
                    val planning = PlanningSnapshot(book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(), AccountingPlanningContext(PlanningIdentitySet(TransactionId(transactionId), TransactionRevisionId(nextId()), BookCommitId(nextId()), List(256) { nextId() }), Instant.parse("2026-08-02T03:00:01Z"), DeviceInstanceId(nextId()), references, listOf(evidence), null))
                    command to planning
                }
                val plan = DeterministicFinancialPlanner.plan(snapshot.first, snapshot.second).success()
                RoomFinancialCommitRepository(database).commit(snapshot.first, plan).success()
                revision += 1
            } finally {
                database.close()
            }
        }
    }

    private suspend fun mutate(mutation: ReferenceMutation) {
        mutateResult(mutation).success()
        revision += 1
    }

    private suspend fun mutateResult(mutation: ReferenceMutation): DomainResult<Unit> = references.mutate(
        ReferenceMutationCommand(
            ReferenceMutationIds(
                BOOK_ID,
                revision,
                nextId(),
                List(16) { nextId() },
                nextId(),
                Instant.ofEpochMilli(seed++),
            ),
            mutation,
        ),
    )

    private suspend fun snapshot() = references.snapshot(BOOK_ID).success()
    private fun nextId(): StableId = id(seed++)
    private fun id(index: Long): StableId = StableId.fromUuid(UUID(0x1212L, index))
    private fun currency(code: String): CurrencyCode = requireNotNull(CurrencyCode.parse(code).getOrNull())
    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String, vararg args: Any?): Long = db.query(sql, args).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x1212L, 1))
    }
}
