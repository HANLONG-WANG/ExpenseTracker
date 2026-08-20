@file:Suppress("LargeClass", "LongMethod", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.StartupInspection
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.ProjectionMaintenancePort
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountSnapshot
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.Book
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.BookId
import app.ledger.finance.domain.BookState
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.GeoPoint
import app.ledger.finance.domain.GeoRadiusFilter
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.LedgerAccountId
import app.ledger.finance.domain.LedgerAccountSnapshot
import app.ledger.finance.domain.LedgerOwnerType
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.LocationRecordId
import app.ledger.finance.domain.MoveTransactionToTrashCommand
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.PlanningAccount
import app.ledger.finance.domain.PlanningCategory
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PlanningSystemLedger
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.ProjectionChangeSet
import app.ledger.finance.domain.RecordExpenseCommand
import app.ledger.finance.domain.RowVersion
import app.ledger.finance.domain.RuleSetVersion
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomFinancialDataDeviceTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        seedReferenceState(database.openHelper.writableDatabase)
    }

    @After
    fun cleanUp() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun atomicCommitIsIdempotentVersionedQueryableAndExactlyRebuildable() = runBlocking {
        val repository = RoomFinancialCommitRepository(database)
        val first = fixture(seed = 10_000L, commandSeed = 20_000L, book = initialBook(), note = "p08-private-first")
        val firstReceipt = repository.commit(first.command, first.plan).success()
        val nextBook = first.snapshot.book.copy(
            headCommitId = first.plan.commit.id,
            localRevision = first.plan.targetLocalRevision,
            firstFinancialCommitAt = first.plan.commit.createdAt,
        )
        val second = fixture(seed = 11_000L, commandSeed = 21_000L, book = nextBook, note = "p08-private-second")
        repository.commit(second.command, second.plan).success()

        val duplicate = repository.commit(first.command, first.plan).success()
        assertEquals(firstReceipt, duplicate)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM command_receipt"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM transaction_revision"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM journal_entry"))
        assertEquals(4L, scalar("SELECT COUNT(*) FROM posting"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM economic_effect"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM budget_effect"))
        assertEquals(3L, scalar("SELECT local_revision FROM book WHERE id = 1"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM journal_entry WHERE base_debit_total_minor <> base_credit_total_minor"))
        assertProjectionRevision(3L)
        val mappedBook = RoomBookRepository(database).current().success()
        assertEquals(second.plan.commit.id, mappedBook.headCommitId)
        assertEquals(second.plan.targetLocalRevision, mappedBook.localRevision)
        assertEquals(null, RoomBookRepository(database).purgeTombstone(first.plan.transactions.single().id).success())

        val queries = RoomTransactionQueryService(database)
        val firstPage = queries.page(emptyFilter(), 1, null).success()
        assertEquals(1, firstPage.items.size)
        assertNotNull(firstPage.nextCursor)
        val secondPage = queries.page(emptyFilter(), 1, firstPage.nextCursor).success()
        assertEquals(1, secondPage.items.size)
        assertTrue(firstPage.items.single().transactionId != secondPage.items.single().transactionId)
        val search = queries.page(emptyFilter().copy(searchText = "p08-private-second"), 10, null).success()
        assertEquals(listOf(second.plan.transactions.single().id), search.items.map { it.transactionId })
        val nearby = queries.withinRadius(
            emptyFilter().copy(
                geoRadius = GeoRadiusFilter(GeoPoint.create(LATITUDE_E7, LONGITUDE_E7).success(), 50),
            ),
            10,
        ).success()
        assertEquals(2, nearby.size)
        assertTrue(nearby.all { it.distanceMeters <= 1 })

        val maintenance: ProjectionMaintenancePort = RoomProjectionMaintenanceService(database)
        val originalAudit = maintenance.audit().success()
        assertTrue("projection differences=${projectionDifferences()}", originalAudit.isConsistent)
        val fullAudit = database.inLedgerTransaction(RoomLedgerIntegrityAudit::run)
        assertTrue("invariants=${fullAudit.failedInvariantIds}", fullAudit.isValid)
        assertEquals(35, fullAudit.database.permanentInvariantViolationCounts.size)
        assertEquals(0, fullAudit.database.postingCurrencyViolationCount)
        assertEquals(0, fullAudit.database.invalidActiveApplyChainCount)
        assertEquals(0, fullAudit.database.nonZeroTrashedTransactionCount)
        assertEquals(StartupInspection.Ready, RoomLedgerStartupInspector().inspect(database))
        database.inLedgerTransaction { connection ->
            connection.execSQL("UPDATE budget_usage_projection SET used_minor = used_minor + 1")
        }
        assertEquals(
            StartupInspection.Maintenance(MaintenanceReason.PROJECTION_REBUILD),
            RoomLedgerStartupInspector().inspect(database),
        )
        assertFalse(maintenance.audit().success().isConsistent)
        val rebuilt = maintenance.rebuild().success()
        assertTrue(rebuilt.isConsistent)
        assertEquals(originalAudit.liveHash, rebuilt.liveHash)
        assertEquals(0L, scalar("SELECT state FROM book WHERE id = 1"))
    }

    private fun projectionDifferences(): Set<String> = database.inLedgerTransaction { connection ->
        val engine = RoomProjectionEngine()
        val before = engine.canonicalTableHashes(connection)
        val revision = connection.query("SELECT local_revision,valuation_revision FROM book WHERE id=1").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0) to cursor.getLong(1)
        }
        connection.execSQL("SAVEPOINT projection_test_diagnostics")
        val after = try {
            engine.rebuildAll(connection, revision.first, revision.second)
            engine.canonicalTableHashes(connection)
        } finally {
            connection.execSQL("ROLLBACK TO SAVEPOINT projection_test_diagnostics")
            connection.execSQL("RELEASE SAVEPOINT projection_test_diagnostics")
        }
        before.keys.filterTo(mutableSetOf()) { table -> before[table] != after[table] }
    }

    @Test
    fun staleExpectedRevisionAndInjectedFailuresNeverLeavePartialState() = runBlocking {
        val fixture = fixture(seed = 12_000L, commandSeed = 22_000L, book = initialBook(), note = "p08-stale")
        val repository = RoomFinancialCommitRepository(database)
        repository.commit(fixture.command, fixture.plan).success()

        val staleDraft = MoveTransactionToTrashCommand(
            commandId = CommandId(id(30_000L)),
            expectedRevisionId = TransactionRevisionId(id(30_001L)),
            payloadHash = hash(1),
            transactionId = fixture.plan.transactions.single().id,
            purgeAfter = Instant.parse("2026-09-01T00:00:00Z"),
            dependencyResolutions = emptyList(),
        )
        val staleCommand = staleDraft.copy(payloadHash = CanonicalFinancialHash.command(staleDraft))
        val stalePlan = fixture.plan.copy(
            commandId = staleCommand.commandId,
            commandType = staleCommand.commandType,
            payloadHash = staleCommand.payloadHash,
            expectedRevisionId = staleCommand.expectedRevisionId,
            targetLocalRevision = LocalRevision.of(3L).success(),
            commit = fixture.plan.commit.copy(
                id = BookCommitId(id(30_002L)),
                parentIds = listOf(fixture.plan.commit.id),
                commandId = staleCommand.commandId,
            ),
            projectionChanges = ProjectionChangeSet(LocalRevision.of(3L).success(), emptyList()),
        )
        val staleResult = repository.commit(staleCommand, stalePlan)
        assertTrue(staleResult is DomainResult.Failure && staleResult.error == DomainViolation.StaleExpectedRevision)
        assertEquals(2L, scalar("SELECT local_revision FROM book WHERE id = 1"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM command_receipt"))

        resetDatabase()
        val rollbackFixture = fixture(seed = 13_000L, commandSeed = 23_000L, book = initialBook(), note = "p08-rollback")
        FinancialCommitPhase.entries.filterNot { it == FinancialCommitPhase.AFTER_DATABASE_COMMIT }.forEach { phase ->
            val failing = RoomFinancialCommitRepository(
                database,
                FinancialCommitFailureInjector { reached -> if (reached == phase) error("injected-$phase") },
            )
            val result = failing.commit(rollbackFixture.command, rollbackFixture.plan)
            assertEquals(DomainResult.Failure(FinanceDataError.DatabaseUnavailable), result)
            assertEquals(1L, scalar("SELECT local_revision FROM book WHERE id = 1"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM book_commit"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM business_transaction"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM transaction_revision"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM journal_entry"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM command_receipt"))
        }
    }

    @Test
    fun processTerminationImmediatelyAfterDurableCommitRecoversReceiptWithoutDuplicateFacts() = runBlocking {
        val fixture = fixture(seed = 14_000L, commandSeed = 24_000L, book = initialBook(), note = "p08-post-commit-crash")
        val crashing = RoomFinancialCommitRepository(
            database,
            FinancialCommitFailureInjector { phase ->
                if (phase == FinancialCommitPhase.AFTER_DATABASE_COMMIT) error("simulated process termination")
            },
        )

        assertTrue(runCatching { crashing.commit(fixture.command, fixture.plan) }.isFailure)
        database.close()
        database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())

        val restarted = RoomFinancialCommitRepository(database)
        val recovered = requireNotNull(restarted.find(fixture.command.commandId).success())
        val replayed = restarted.commit(fixture.command, fixture.plan).success()
        assertEquals(recovered, replayed)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM command_receipt"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM transaction_revision"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM journal_entry"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM posting"))
        assertEquals(2L, scalar("SELECT local_revision FROM book WHERE id=1"))
    }

    @Test
    fun applicationUpdateProjectionContractChangeForcesAndCompletesDeterministicRebuild() = runBlocking {
        val fixture = fixture(seed = 15_000L, commandSeed = 25_000L, book = initialBook(), note = "p08-projection-update")
        RoomFinancialCommitRepository(database).commit(fixture.command, fixture.plan).success()
        database.inLedgerTransaction { connection ->
            connection.execSQL("UPDATE projection_contract_state SET contract_version=1 WHERE id=1")
        }
        database.close()
        database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())

        val maintenance = RoomProjectionMaintenanceService(database)
        val before = maintenance.startupCheck().success()
        assertEquals(app.ledger.finance.application.StartupDisposition.MAINTENANCE_REQUIRED, before.disposition)
        assertTrue("PROJECTION_VERSION_MISMATCH" in before.reasonCodes)

        val rebuilt = maintenance.rebuild().success()
        assertTrue(rebuilt.isConsistent)
        assertEquals(2L, scalar("SELECT contract_version FROM projection_contract_state WHERE id=1"))
        assertEquals(2L, scalar("SELECT rebuilt_at_local_revision FROM projection_contract_state WHERE id=1"))
        assertEquals(app.ledger.finance.application.StartupDisposition.READY, maintenance.startupCheck().success().disposition)
    }

    @Test
    fun halfMillionRowsUseBoundedKeysetPagingAndFtsWithoutDeepOffset() = runBlocking {
        seedHalfMillionQueryRows()
        val query = RoomTransactionQueryService(database)
        val started = SystemClock.elapsedRealtime()
        val first = query.page(emptyFilter(), 40, null).success()
        val firstElapsed = SystemClock.elapsedRealtime() - started
        assertEquals(40, first.items.size)
        assertNotNull(first.nextCursor)
        val second = query.page(emptyFilter(), 40, first.nextCursor).success()
        assertEquals(40, second.items.size)
        assertTrue(first.items.map { it.transactionId }.toSet().intersect(second.items.map { it.transactionId }.toSet()).isEmpty())
        val searched = query.page(emptyFilter().copy(searchText = "needle-p15"), 40, null).success()
        assertEquals(1, searched.items.size)
        assertTrue(firstElapsed < 5_000L)
        val compiled = TransactionSqlCompiler.compile(emptyFilter(), first.nextCursor, 41)
        assertFalse(compiled.sql.contains(" OFFSET ", ignoreCase = true))
        val plan = database.readLedger { db ->
            db.query("EXPLAIN QUERY PLAN ${compiled.sql}", compiled.arguments.toTypedArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
            }
        }
        assertTrue(plan.any { it.contains("ix_current_transaction_keyset") || it.contains("INDEX") })
        assertEquals(HALF_MILLION.toLong(), scalar("SELECT COUNT(*) FROM current_transaction_projection"))
    }

    private fun seedHalfMillionQueryRows() {
        val db = database.openHelper.writableDatabase
        database.inLedgerTransaction { connection ->
            val insertTransaction = connection.compileStatement(
                "INSERT INTO business_transaction(id,uid,kind,current_revision_id,lifecycle_state,created_commit_id,last_commit_id,row_version,trashed_at,purge_after,content_hash) VALUES(?,?,0,NULL,0,1,1,1,NULL,NULL,?)",
            )
            val insertRevision = connection.compileStatement(
                "INSERT INTO transaction_revision(id,uid,transaction_id,revision_no,action,resulting_state,previous_revision_id,created_commit_id,created_at,occurred_at,zone_id,local_date,category_id,statistical_nature_snapshot,merchant_id,project_id,goal_id,location_record_id,note,amount_expression,source_type,source_reference_uid,content_hash) VALUES(?,?,?,1,0,0,NULL,1,?,?,?, ?,?,?,NULL,NULL,NULL,NULL,?,NULL,0,NULL,?)",
            )
            val updateTransaction = connection.compileStatement("UPDATE business_transaction SET current_revision_id=? WHERE id=?")
            val insertProjection = connection.compileStatement(
                "INSERT INTO current_transaction_projection(transaction_id,transaction_uid,kind,state,current_revision_id,occurred_at,local_date,primary_account_id,secondary_account_id,card_id,category_id,merchant_id,project_id,goal_id,settlement_activity_id,payer_participant_id,input_amount_minor,input_currency,account_amount_minor,account_currency,economic_base_minor,note_preview,has_attachment,has_location,is_refund,is_refunded,has_installment,source_type,as_of_local_revision) VALUES(?,?,0,0,?,?,?, ?,NULL,NULL,?,NULL,NULL,NULL,NULL,NULL,1,'JPY',1,'JPY',1,?,0,0,0,0,0,0,1)",
            )
            val insertFts = connection.compileStatement(
                "INSERT INTO transaction_fts(transaction_id,category_name,merchant_name,merchant_aliases,note,project_name,settlement_activity_name,participant_names,attachment_names,lifecycle_state) VALUES(?, 'Food','','',?,'','','','',0)",
            )
            repeat(HALF_MILLION) { index ->
                val transactionInternal = 10_000L + index
                val revisionInternal = 600_000L + index
                val transactionUid = id(1_000_000L + index).bytes
                val revisionUid = id(2_000_000L + index).bytes
                val occurredAt = 1_800_000_000_000L + index
                val note = if (index == HALF_MILLION - 1) "needle-p15" else "row-$index"
                insertTransaction.clearBindings()
                insertTransaction.bindLong(1, transactionInternal)
                insertTransaction.bindBlob(2, transactionUid)
                insertTransaction.bindBlob(3, ByteArray(32) { 8 })
                insertTransaction.executeInsert()
                insertRevision.clearBindings()
                insertRevision.bindLong(1, revisionInternal)
                insertRevision.bindBlob(2, revisionUid)
                insertRevision.bindLong(3, transactionInternal)
                insertRevision.bindLong(4, occurredAt)
                insertRevision.bindLong(5, occurredAt)
                insertRevision.bindString(6, "Asia/Tokyo")
                insertRevision.bindLong(7, 20260803)
                insertRevision.bindLong(8, CATEGORY_ID.value.internalId())
                insertRevision.bindLong(9, StatisticalNature.CONSUMPTION_EXPENSE.ordinal.toLong())
                insertRevision.bindString(10, note)
                insertRevision.bindBlob(11, ByteArray(32) { 9 })
                insertRevision.executeInsert()
                updateTransaction.clearBindings()
                updateTransaction.bindLong(1, revisionInternal)
                updateTransaction.bindLong(2, transactionInternal)
                updateTransaction.executeUpdateDelete()
                insertProjection.clearBindings()
                insertProjection.bindLong(1, transactionInternal)
                insertProjection.bindBlob(2, transactionUid)
                insertProjection.bindLong(3, revisionInternal)
                insertProjection.bindLong(4, occurredAt)
                insertProjection.bindLong(5, 20260803)
                insertProjection.bindLong(6, ACCOUNT_ID.value.internalId())
                insertProjection.bindLong(7, CATEGORY_ID.value.internalId())
                insertProjection.bindString(8, note)
                insertProjection.executeInsert()
                insertFts.clearBindings()
                insertFts.bindLong(1, transactionInternal)
                insertFts.bindString(2, note)
                insertFts.executeInsert()
            }
        }
        db.query("PRAGMA optimize").close()
    }

    private fun resetDatabase() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        seedReferenceState(database.openHelper.writableDatabase)
    }

    private fun fixture(seed: Long, commandSeed: Long, book: Book, note: String): PlannedFixture {
        val references = references()
        val money = Money(1_000L, JPY)
        val positive = PositiveMoney.from(money).success()
        val accountAmount = AccountAmount.create(references.accounts.single().account, money).success()
        val input = NewTransactionInput(
            TransactionContextInput(
                occurredAt = EffectiveTime.fromInstant(Instant.ofEpochSecond(seed), ZoneId.of("Asia/Tokyo")),
                accrualDate = Instant.ofEpochSecond(seed).atZone(ZoneId.of("Asia/Tokyo")).toLocalDate(),
                budgetMonth = YearMonth.from(Instant.ofEpochSecond(seed).atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()),
                merchantId = null,
                projectId = null,
                goalId = null,
                locationRecordId = LOCATION_ID,
                note = note,
                amountExpression = "1000",
                source = TransactionSource.MANUAL,
                sourceReferenceId = null,
                statementAssignment = null,
                attachmentIds = emptyList(),
            ),
            ExpensePayload(
                classification = CategoryAssignment(
                    CATEGORY_ID,
                    CategoryDirection.EXPENSE,
                    StatisticalNature.CONSUMPTION_EXPENSE,
                ),
                payer = ExpensePayer.LocalAccount(accountAmount, null),
                primaryAmount = positive,
                settlementActivityId = null,
                settlementShares = emptyList(),
                installmentPlanId = null,
            ),
        )
        val draft = RecordExpenseCommand(CommandId(id(commandSeed)), hash(0), input)
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val evidence = FrozenAmountEvidence.create(
            AmountEvidenceKey(AmountRole.PRIMARY, 0),
            positive,
            positive,
            positive,
            ACCOUNT_ID,
            null,
            null,
        ).success()
        val snapshot = PlanningSnapshot(
            book = book,
            currentTransaction = null,
            currentRevision = null,
            dependencies = emptyList(),
            reversedApplyEntryIds = emptySet(),
            refundStatuses = emptyList(),
            budgetRevision = null,
            participants = emptyList(),
            accountingContext = AccountingPlanningContext(
                identities = PlanningIdentitySet(
                    TransactionId(id(seed)),
                    TransactionRevisionId(id(seed + 1)),
                    BookCommitId(id(seed + 2)),
                    (seed + 10..seed + 250).map(::id),
                ),
                createdAt = Instant.ofEpochSecond(seed + 3),
                deviceInstanceId = DeviceInstanceId(id(seed + 4)),
                references = references,
                amountEvidence = listOf(evidence),
                currentFacts = null,
            ),
        )
        val plan = DeterministicFinancialPlanner.plan(command, snapshot).success()
        return PlannedFixture(command, snapshot, plan)
    }

    private fun references(): PlanningReferenceData {
        val accountLedger = ledger(800L, LedgerAccountClass.ASSET, DebitCredit.DEBIT)
        val account = PlanningAccount(
            AccountSnapshot(
                ACCOUNT_ID,
                accountLedger.id,
                UserAccountType.BANK,
                JPY,
                EntityStatus.ACTIVE,
                RowVersion.of(1L).success(),
                false,
            ),
            accountLedger,
        )
        val systems = SystemLedgerCode.entries.mapIndexed { index, code ->
            val accountClass = when (code) {
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
            PlanningSystemLedger(
                code,
                ledger(
                    900L + index,
                    accountClass,
                    if (accountClass in setOf(LedgerAccountClass.INCOME, LedgerAccountClass.EQUITY)) {
                        DebitCredit.CREDIT
                    } else {
                        DebitCredit.DEBIT
                    },
                ),
            )
        }
        return PlanningReferenceData(
            accounts = listOf(account),
            cards = emptyList(),
            categories = listOf(
                PlanningCategory(
                    CATEGORY_ID,
                    CATEGORY_ID,
                    CategoryDirection.EXPENSE,
                    StatisticalNature.CONSUMPTION_EXPENSE,
                    CategoryStatus.ACTIVE,
                ),
            ),
            projects = emptyList(),
            goals = emptyList(),
            systemLedgers = systems,
            loanLedgers = emptyList(),
            settlementLedgers = emptyList(),
        )
    }

    private fun seedReferenceState(connection: SupportSQLiteDatabase) {
        connection.execSQL(
            "INSERT INTO rule_set_version(version, algorithm_hash, activated_at, retired_at) VALUES (1, ?, 0, NULL)",
            arrayOf<Any>(ByteArray(32) { 1 }),
        )
        connection.execSQL(
            "INSERT INTO book_commit(id, uid, local_revision, kind, command_uid, device_instance_uid, created_at, root_hash) " +
                "VALUES (1, ?, 1, 0, NULL, ?, 0, ?)",
            arrayOf<Any>(GENESIS_COMMIT.value.bytes, id(3).bytes, ByteArray(32) { 2 }),
        )
        connection.execSQL(
            "INSERT INTO book(id, uid, base_currency, default_zone_id, head_commit_id, local_revision, valuation_revision, " +
                "rule_set_version, created_at, first_financial_commit_at, state) VALUES (1, ?, 'JPY', 'Asia/Tokyo', 1, 1, 1, 1, 0, NULL, 0)",
            arrayOf<Any>(BOOK_ID.value.bytes),
        )
        references().systemLedgers.forEach { system -> insertLedger(connection, system.ledger, LedgerOwnerType.SYSTEM, system.code.name) }
        val accountLedger = references().accounts.single().ledger
        insertLedger(connection, accountLedger, LedgerOwnerType.USER_ACCOUNT, null)
        connection.execSQL(
            "INSERT INTO user_account(id, uid, ledger_account_id, type, name, currency_code, status, icon_key, color_argb, sort_order, " +
                "last_commit_id, row_version, content_hash) VALUES (?, ?, ?, ?, 'P08 account', 'JPY', ?, 'bank', 0, 0, 1, 1, ?)",
            arrayOf<Any>(
                ACCOUNT_ID.value.internalId(),
                ACCOUNT_ID.value.bytes,
                accountLedger.id.value.internalId(),
                UserAccountType.BANK.ordinal,
                EntityStatus.ACTIVE.ordinal,
                ByteArray(32) { 3 },
            ),
        )
        connection.execSQL(
            "INSERT INTO category(id, uid, direction, parent_id, depth, name, normalized_name, icon_key, color_argb, sort_order, status, " +
                "statistical_nature, last_commit_id, row_version) VALUES (?, ?, ?, NULL, 1, 'Food', 'food', 'food', 0, 0, ?, ?, 1, 1)",
            arrayOf<Any>(
                CATEGORY_ID.value.internalId(),
                CATEGORY_ID.value.bytes,
                CategoryDirection.EXPENSE.ordinal,
                CategoryStatus.ACTIVE.ordinal,
                StatisticalNature.CONSUMPTION_EXPENSE.ordinal,
            ),
        )
        connection.execSQL(
            "INSERT INTO location_record(id, uid, lat_e7, lon_e7, accuracy_mm, captured_at, source, provider, place_id, created_commit_id) " +
                "VALUES (?, ?, ?, ?, 1000, 0, 0, NULL, NULL, 1)",
            arrayOf<Any>(LOCATION_ID.value.internalId(), LOCATION_ID.value.bytes, LATITUDE_E7, LONGITUDE_E7),
        )
    }

    private fun insertLedger(
        connection: SupportSQLiteDatabase,
        ledger: LedgerAccountSnapshot,
        owner: LedgerOwnerType,
        systemCode: String?,
    ) {
        connection.execSQL(
            "INSERT INTO ledger_account(id, uid, owner_type, account_class, normal_side, currency_code, parent_ledger_account_id, " +
                "system_code, status, created_commit_id) VALUES (?, ?, ?, ?, ?, 'JPY', NULL, ?, ?, 1)",
            arrayOf<Any?>(
                ledger.id.value.internalId(),
                ledger.id.value.bytes,
                owner.ordinal,
                ledger.accountClass.ordinal,
                ledger.normalSide.ordinal,
                systemCode,
                EntityStatus.ACTIVE.ordinal,
            ),
        )
    }

    private fun assertProjectionRevision(expected: Long) {
        VERSIONED_PROJECTIONS.forEach { table ->
            assertEquals(0L, scalar("SELECT COUNT(*) FROM $table WHERE as_of_local_revision > $expected"))
        }
        assertEquals(15L, scalar("SELECT COUNT(*) FROM projection_family_state WHERE as_of_local_revision=$expected"))
        assertEquals(expected, scalar("SELECT as_of_local_revision FROM widget_book_snapshot WHERE id = 1"))
        assertEquals(1L, scalar("SELECT as_of_valuation_revision FROM widget_book_snapshot WHERE id = 1"))
    }

    private fun emptyFilter(): TransactionFilter = TransactionFilter()

    private fun initialBook(): Book = Book(
        BOOK_ID,
        JPY,
        ZoneId.of("Asia/Tokyo"),
        GENESIS_COMMIT,
        LocalRevision.of(1L).success(),
        LocalRevision.of(1L).success(),
        RuleSetVersion.of(1).success(),
        Instant.EPOCH,
        null,
        BookState.READY,
    )

    private fun ledger(idValue: Long, accountClass: LedgerAccountClass, normalSide: DebitCredit) = LedgerAccountSnapshot(
        LedgerAccountId(id(idValue)),
        accountClass,
        normalSide,
        JPY,
        EntityStatus.ACTIVE,
    )

    private fun scalar(sql: String): Long = database.readLedger { connection ->
        connection.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0L, value))

    private fun hash(value: Int): Hash256 = Hash256.fromBytes(ByteArray(32) { value.toByte() }).success()

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error("Expected success, got ${error.code}")
    }

    private data class PlannedFixture(
        val command: RecordExpenseCommand,
        val snapshot: PlanningSnapshot,
        val plan: FinancialMutationPlan,
    )

    private companion object {
        const val DATABASE_NAME = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME
        val PASSPHRASE = ByteArray(32) { index -> (0x48 + index).toByte() }
        val JPY = CurrencyCode.parse("JPY").let { (it as DomainResult.Success).value }
        val BOOK_ID = BookId(StableId.fromUuid(UUID(0L, 1L)))
        val GENESIS_COMMIT = BookCommitId(StableId.fromUuid(UUID(0L, 2L)))
        val ACCOUNT_ID = UserAccountId(StableId.fromUuid(UUID(0L, 710L)))
        val CATEGORY_ID = CategoryId(StableId.fromUuid(UUID(0L, 701L)))
        val LOCATION_ID = LocationRecordId(StableId.fromUuid(UUID(0L, 750L)))
        const val LATITUDE_E7 = 356_817_000
        const val LONGITUDE_E7 = 1397_672_000
        const val HALF_MILLION = 500_000
        val VERSIONED_PROJECTIONS = listOf(
            "current_transaction_projection", "account_balance_current", "account_balance_daily",
            "refund_status_projection", "budget_usage_projection", "project_usage_projection", "goal_balance_projection",
            "credit_statement_projection", "credit_account_projection", "installment_progress_projection",
            "loan_progress_projection", "settlement_position_projection", "widget_account_snapshot", "widget_credit_snapshot",
            "widget_goal_snapshot",
        )
    }
}
