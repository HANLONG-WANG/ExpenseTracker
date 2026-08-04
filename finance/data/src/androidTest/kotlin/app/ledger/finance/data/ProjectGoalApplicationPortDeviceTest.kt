@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength")

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
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.ChangeProjectStatusRequest
import app.ledger.finance.application.GoalMovementMutationIds
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.PlanningMutationIds
import app.ledger.finance.application.ProjectTransactionPageRequest
import app.ledger.finance.application.RecordGoalMovementRequest
import app.ledger.finance.application.RefundAllocationDraft
import app.ledger.finance.application.RefundAmountDraft
import app.ledger.finance.application.RefundWriteIds
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.application.SaveGoalRequest
import app.ledger.finance.application.SaveProjectRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
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
class ProjectGoalApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var planning: SecureRoomProjectGoalApplicationPort
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort
    private lateinit var refunds: SecureRoomRefundApplicationPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        SecureRoomLedgerInitializationPort(context, keys).apply {
            initialize(
                InitializeLedgerCommand(
                    LedgerGenesisIds(BOOK_ID, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()),
                    JPY,
                    ZONE,
                    Instant.ofEpochMilli(1_000),
                ),
            ).success()
            createFirstAccount(
                BOOK_ID,
                InitialAccountCommand(ACCOUNT_ID, id(201), id(202), id(203), id(204), Instant.ofEpochMilli(2_000), UserAccountType.CASH, "Wallet", JPY, "account", 0xff006c4c.toInt()),
            ).success()
            createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.ofEpochMilli(3_000), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
            ).success()
        }
        references = SecureRoomReferenceDataManagementPort(context, keys)
        planning = SecureRoomProjectGoalApplicationPort(context, keys, references)
        ordinary = SecureRoomOrdinaryTransactionEntryPort(context, keys, references)
        refunds = SecureRoomRefundApplicationPort(context, keys, references)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun projectAndGoalFactsRemainIndependentRebuildableAndOptimisticallyLocked() = runBlocking {
        saveGoal(seed = 1_000L)
        saveProject(seed = 2_000L, included = true, expectedRowVersion = null, status = ProjectStatus.ACTIVE)
        var snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(listOf(PROJECT_ID), snapshot.goals.single().boundProjects)
        assertEquals(GOAL_ID, snapshot.projects.single().goalId)

        val actualBeforeMovement = accountBalance()
        val allocation = movement(3_000L, GoalMovementKind.ALLOCATE, 1_000L, snapshot.goals.single().rowVersion)
        val firstReceipt = planning.recordGoalMovement(allocation).success()
        assertEquals(firstReceipt, planning.recordGoalMovement(allocation).success())
        snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(actualBeforeMovement, accountBalance())
        assertEquals(1_000L, snapshot.goals.single().balanceMinor)
        assertEquals(-1_000L, snapshot.goals.single().accountAvailableMinor)
        assertTrue(snapshot.goals.single().accountUnderfunded)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM goal_movement"))

        val excessiveRelease = planning.recordGoalMovement(movement(3_500L, GoalMovementKind.RELEASE, 1_001L, snapshot.goals.single().rowVersion))
        assertTrue(excessiveRelease is DomainResult.Failure)
        assertEquals(1_000L, planning.snapshot(BOOK_ID).success().goals.single().balanceMinor)

        ordinary.submit(expense(4_000L, 300L, TRANSACTION_A, REVISION_A, project = PROJECT_ID, goal = GOAL_ID)).success()
        snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(300L, snapshot.projects.single().usedBaseMinor)
        assertEquals(700L, snapshot.goals.single().balanceMinor)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM budget_effect"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM project_effect WHERE monthly_budget_inclusion_snapshot=1"))

        saveProject(seed = 5_000L, included = false, expectedRowVersion = snapshot.projects.single().rowVersion, status = ProjectStatus.ACTIVE)
        ordinary.submit(expense(6_000L, 200L, TRANSACTION_B, REVISION_B, project = PROJECT_ID, goal = GOAL_ID)).success()
        snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(500L, snapshot.projects.single().usedBaseMinor)
        assertEquals(500L, snapshot.goals.single().balanceMinor)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM budget_effect"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM project_effect WHERE monthly_budget_inclusion_snapshot=0"))

        refunds.submit(refund(seed = 7_000L, amount = 100L)).success()
        snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(500L, snapshot.projects.single().usedBaseMinor)
        assertEquals(100L, snapshot.projects.single().restoredBaseMinor)
        assertEquals(600L, snapshot.projects.single().remainingBaseMinor)
        assertEquals(600L, snapshot.goals.single().balanceMinor)
        assertEquals(3, snapshot.projects.single().transactions.size)
        assertTrue(snapshot.projects.single().cashflow.isNotEmpty())
        val pagedIds = buildList {
            var cursor: app.ledger.finance.application.ProjectTransactionCursor? = null
            do {
                val page = planning.projectTransactionPage(ProjectTransactionPageRequest(BOOK_ID, PROJECT_ID, 1, cursor)).success()
                addAll(page.items.map { it.id })
                cursor = page.nextCursor
            } while (cursor != null)
        }
        assertEquals(3, pagedIds.size)
        assertEquals(3, pagedIds.toSet().size)

        changeProjectStatus(seed = 8_000L, expectedRowVersion = snapshot.projects.single().rowVersion, status = ProjectStatus.ARCHIVED)
        val rejected = ordinary.submit(expense(9_000L, 50L, TRANSACTION_C, REVISION_C, project = PROJECT_ID, goal = null))
        assertTrue(rejected is DomainResult.Failure)
        assertTrue((rejected as DomainResult.Failure).error is DomainViolation.InvalidField)
        snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(ProjectStatus.ARCHIVED, snapshot.projects.single().status)
        assertEquals(500L, snapshot.projects.single().usedBaseMinor)
        assertEquals(100L, snapshot.projects.single().restoredBaseMinor)

        changeProjectStatus(seed = 10_000L, expectedRowVersion = snapshot.projects.single().rowVersion, status = ProjectStatus.ACTIVE)
        snapshot = planning.snapshot(BOOK_ID).success()
        assertEquals(ProjectStatus.ACTIVE, snapshot.projects.single().status)
        assertFalse(snapshot.projects.single().includedInMonthlyBudget)

        val stale = saveProjectResult(seed = 11_000L, included = true, expectedRowVersion = 1L, status = ProjectStatus.ACTIVE)
        assertTrue(stale is DomainResult.Failure)
        assertCanonicalRebuildStable()
    }

    private suspend fun saveGoal(seed: Long) {
        val snapshot = planning.snapshot(BOOK_ID).success()
        planning.saveGoal(
            SaveGoalRequest(
                planningIds(snapshot.localRevision.value, seed),
                GOAL_ID,
                null,
                ACCOUNT_ID,
                "Emergency",
                2_000L,
                LocalDate.of(2027, 8, 1),
                200L,
                GoalStatus.ACTIVE,
                Instant.ofEpochMilli(seed),
            ),
        ).success()
    }

    private suspend fun saveProject(seed: Long, included: Boolean, expectedRowVersion: Long?, status: ProjectStatus) {
        saveProjectResult(seed, included, expectedRowVersion, status).success()
    }

    private suspend fun changeProjectStatus(seed: Long, expectedRowVersion: Long, status: ProjectStatus) {
        val snapshot = planning.snapshot(BOOK_ID).success()
        planning.changeProjectStatus(
            ChangeProjectStatusRequest(
                planningIds(snapshot.localRevision.value, seed),
                PROJECT_ID,
                expectedRowVersion,
                status,
                Instant.ofEpochMilli(seed),
            ),
        ).success()
    }

    private suspend fun saveProjectResult(seed: Long, included: Boolean, expectedRowVersion: Long?, status: ProjectStatus): DomainResult<Unit> {
        val snapshot = planning.snapshot(BOOK_ID).success()
        return planning.saveProject(
            SaveProjectRequest(
                planningIds(snapshot.localRevision.value, seed),
                PROJECT_ID,
                expectedRowVersion,
                "Japan trip",
                "Receipts and shared costs",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                1_000L,
                included,
                GOAL_ID,
                status,
                Instant.ofEpochMilli(seed),
            ),
        )
    }

    private fun movement(seed: Long, kind: GoalMovementKind, amount: Long, expectedRowVersion: Long) = RecordGoalMovementRequest(
        GoalMovementMutationIds(BOOK_ID, CommandId(id(seed)), id(seed + 1), id(seed + 2), id(seed + 3), id(seed + 4)),
        GOAL_ID,
        expectedRowVersion,
        kind,
        amount,
        Instant.ofEpochMilli(seed),
        Instant.ofEpochMilli(seed),
    )

    private fun expense(
        seed: Long,
        amount: Long,
        transaction: StableId,
        revision: StableId,
        project: StableId?,
        goal: StableId?,
    ) = OrdinaryTransactionWriteRequest(
        OrdinaryTransactionWriteIds(BOOK_ID, id(seed), transaction, revision, id(seed + 1), id(seed + 2), (seed + 10..seed + 400).map(::id), (seed + 500..seed + 507).map(::id)),
        null,
        OrdinaryDirection.EXPENSE,
        CATEGORY_ID,
        OrdinaryAmountDraft(amount.toString(), amount, JPY, amount, amount),
        ACCOUNT_ID,
        null,
        null,
        Instant.parse("2026-08-04T03:00:00Z").plusMillis(seed),
        ZONE,
        LocalDate.of(2026, 8, 4),
        project,
        goal,
        null,
        emptyList(),
        null,
        null,
        "project purchase",
        emptyList(),
        TransactionSource.MANUAL,
        null,
        Instant.ofEpochMilli(seed),
    )

    private fun refund(seed: Long, amount: Long) = RefundWriteRequest(
        RefundWriteIds(BOOK_ID, CommandId(id(seed)), id(seed + 1), id(seed + 2), id(seed + 3), id(seed + 4), (seed + 10..seed + 500).map(::id), (seed + 600..seed + 607).map(::id)),
        listOf(RefundAllocationDraft(TRANSACTION_B, REVISION_B, amount, amount)),
        RefundAmountDraft(amount, JPY, ACCOUNT_ID, amount, amount, null, null),
        null,
        false,
        CATEGORY_ID,
        null,
        PROJECT_ID,
        GOAL_ID,
        null,
        emptyList(),
        Instant.parse("2026-08-10T03:00:00Z"),
        ZONE,
        LocalDate.of(2026, 8, 10),
        LocalDate.of(2026, 8, 4),
        YearMonth.of(2026, 8),
        RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH,
        RefundProjectPolicy.USE_SELECTED_PROJECT,
        RefundGoalPolicy.USE_SELECTED_GOAL,
        RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE,
        false,
        false,
        amount.toString(),
        null,
        emptyList(),
        Instant.ofEpochMilli(seed),
    )

    private fun planningIds(localRevision: Long, seed: Long) = PlanningMutationIds(BOOK_ID, localRevisionValue(localRevision), id(seed), id(seed + 1), id(seed + 2))

    private fun localRevisionValue(value: Long) = (app.ledger.finance.domain.LocalRevision.of(value) as DomainResult.Success).value

    private fun accountBalance(): Long = scalar("SELECT normal_balance_minor FROM account_balance_current abc JOIN user_account ua ON ua.id=abc.account_id WHERE ua.uid=?", ACCOUNT_ID.bytes)

    private fun assertCanonicalRebuildStable() = withDatabase { db ->
        val before = RoomProjectionEngine().canonicalHash(db)
        val book = RoomBookRepository.mapCurrent(db)
        RoomProjectionEngine().rebuildAll(db, book.localRevision.value, book.valuationRevision.value, LocalDate.of(2026, 8, 10).toStorageInt())
        assertEquals(before, RoomProjectionEngine().canonicalHash(db))
        assertEquals(
            "ok",
            db.query("PRAGMA integrity_check").use {
                it.moveToFirst()
                it.getString(0)
            },
        )
        assertEquals(
            0L,
            db.query("SELECT COUNT(*) FROM pragma_foreign_key_check").use {
                it.moveToFirst()
                it.getLong(0)
            },
        )
    }

    private fun scalar(sql: String, vararg args: Any?): Long = withDatabase { db ->
        db.query(sql, args).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private fun <T> withDatabase(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> T): T = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.inLedgerTransaction(block)
        } finally {
            database.close()
        }
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1818L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        fun currency(code: String): CurrencyCode = requireNotNull(CurrencyCode.parse(code).getOrNull())
        val BOOK_ID = StableId.fromUuid(UUID(0x1818L, 1))
        val ACCOUNT_ID = StableId.fromUuid(UUID(0x1818L, 200))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x1818L, 210))
        val GOAL_ID = StableId.fromUuid(UUID(0x1818L, 300))
        val PROJECT_ID = StableId.fromUuid(UUID(0x1818L, 400))
        val TRANSACTION_A = StableId.fromUuid(UUID(0x1818L, 500))
        val REVISION_A = StableId.fromUuid(UUID(0x1818L, 501))
        val TRANSACTION_B = StableId.fromUuid(UUID(0x1818L, 600))
        val REVISION_B = StableId.fromUuid(UUID(0x1818L, 601))
        val TRANSACTION_C = StableId.fromUuid(UUID(0x1818L, 700))
        val REVISION_C = StableId.fromUuid(UUID(0x1818L, 701))
        val JPY = currency("JPY")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
