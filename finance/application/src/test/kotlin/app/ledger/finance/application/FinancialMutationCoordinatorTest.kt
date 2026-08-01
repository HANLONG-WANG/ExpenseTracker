package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.Book
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.BookId
import app.ledger.finance.domain.BookState
import app.ledger.finance.domain.BudgetAdjustment
import app.ledger.finance.domain.BudgetAdjustmentId
import app.ledger.finance.domain.BudgetAdjustmentKind
import app.ledger.finance.domain.BudgetAdjustmentScope
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CommitDraft
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.ProjectionChangeSet
import app.ledger.finance.domain.RecordBudgetAdjustmentCommand
import app.ledger.finance.domain.RuleSetVersion
import app.ledger.finance.domain.StableEntityReference
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class FinancialMutationCoordinatorTest {
    @Test
    fun `same command returns immutable first receipt without replanning`() = runTest {
        val command = command()
        val receipt = receipt(command)
        val receiptRepository = mockk<CommandReceiptRepository>()
        val snapshotRepository = mockk<FinancialPlanningSnapshotRepository>()
        val planner = mockk<FinancialPlanningPort>()
        val commitRepository = mockk<AtomicFinancialCommitRepository>()
        coEvery { receiptRepository.find(command.commandId) } returns DomainResult.Success(receipt)
        val coordinator = coordinator(receiptRepository, snapshotRepository, planner, commitRepository)

        coordinator.execute(command) shouldBe DomainResult.Success(receipt)

        coVerify(exactly = 0) { snapshotRepository.load(any()) }
        verify(exactly = 0) { planner.plan(any(), any()) }
        coVerify(exactly = 0) { commitRepository.commit(any()) }
    }

    @Test
    fun `same id with another payload is rejected`() = runTest {
        val command = command()
        val conflicting = command.copy(payloadHash = hash(99))
        val receiptRepository = mockk<CommandReceiptRepository>()
        coEvery { receiptRepository.find(command.commandId) } returns DomainResult.Success(receipt(command))
        val coordinator = coordinator(
            receiptRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        coordinator.execute(conflicting) shouldBe DomainResult.Failure(DomainViolation.DuplicateCommandPayloadMismatch)
    }

    @Test
    fun `new command is planned validated and atomically committed`() = runTest {
        val command = command()
        val book = book()
        val snapshot = snapshot(book)
        val plan = plan(command, book)
        val receipt = receipt(command)
        val receiptRepository = mockk<CommandReceiptRepository>()
        val snapshotRepository = mockk<FinancialPlanningSnapshotRepository>()
        val planner = mockk<FinancialPlanningPort>()
        val commitRepository = mockk<AtomicFinancialCommitRepository>()
        coEvery { receiptRepository.find(command.commandId) } returns DomainResult.Success(null)
        coEvery { snapshotRepository.load(command) } returns DomainResult.Success(snapshot)
        every { planner.plan(command, snapshot) } returns DomainResult.Success(plan)
        coEvery { commitRepository.commit(plan) } returns DomainResult.Success(receipt)
        val coordinator = coordinator(receiptRepository, snapshotRepository, planner, commitRepository)

        coordinator.execute(command) shouldBe DomainResult.Success(receipt)
        coVerify(exactly = 1) { commitRepository.commit(plan) }
    }

    private fun coordinator(
        receipts: CommandReceiptRepository,
        snapshots: FinancialPlanningSnapshotRepository,
        planner: FinancialPlanningPort,
        commits: AtomicFinancialCommitRepository,
    ): DefaultFinancialMutationCoordinator = DefaultFinancialMutationCoordinator(
        writeGate = object : LedgerWriteGate {
            override suspend fun <T> execute(block: suspend () -> T): T = block()
        },
        receiptRepository = receipts,
        snapshotRepository = snapshots,
        planner = planner,
        commitRepository = commits,
    )

    private fun command(): RecordBudgetAdjustmentCommand {
        val adjustment = BudgetAdjustment(
            id = BudgetAdjustmentId(id(10)),
            month = YearMonth.of(2026, 8),
            scope = BudgetAdjustmentScope.TOTAL,
            categoryId = null,
            amountBaseMinor = 100L,
            kind = BudgetAdjustmentKind.INCREASE_AVAILABLE,
            createdCommitId = BookCommitId(id(11)),
            reversalOfId = null,
        )
        return RecordBudgetAdjustmentCommand(CommandId(id(12)), hash(12), adjustment)
    }

    private fun book(): Book = Book(
        id = BookId(id(1)),
        baseCurrency = CurrencyCode.parse("JPY").success(),
        defaultZoneId = ZoneId.of("Asia/Tokyo"),
        headCommitId = BookCommitId(id(2)),
        localRevision = LocalRevision.of(1L).success(),
        valuationRevision = LocalRevision.of(1L).success(),
        ruleSetVersion = RuleSetVersion.of(1).success(),
        createdAt = Instant.EPOCH,
        firstFinancialCommitAt = Instant.EPOCH,
        state = BookState.READY,
    )

    private fun snapshot(book: Book): PlanningSnapshot = PlanningSnapshot(
        book,
        null,
        null,
        emptyList(),
        emptySet(),
        emptyList(),
        null,
        emptyList(),
    )

    private fun plan(command: RecordBudgetAdjustmentCommand, book: Book): FinancialMutationPlan {
        val target = book.localRevision.next().success()
        return FinancialMutationPlan(
            commandId = command.commandId,
            commandType = command.commandType,
            payloadHash = command.payloadHash,
            expectedRevisionId = null,
            targetLocalRevision = target,
            commit = CommitDraft(
                BookCommitId(id(13)),
                CommitKind.USER_MUTATION,
                listOf(book.headCommitId),
                Instant.ofEpochSecond(1),
                command.commandId,
                DeviceInstanceId(id(14)),
                hash(14),
            ),
            transactions = emptyList(),
            revisions = emptyList(),
            revisionAmounts = emptyList(),
            fxRateSnapshots = emptyList(),
            journalBundles = emptyList(),
            economicEffects = emptyList(),
            budgetEffects = emptyList(),
            projectEffects = emptyList(),
            goalEffects = emptyList(),
            statementEffects = emptyList(),
            loanEffects = emptyList(),
            settlementEffects = emptyList(),
            refundAllocations = emptyList(),
            goalMovements = emptyList(),
            budgetAdjustments = listOf(command.adjustment),
            purgeTombstones = emptyList(),
            blobGcCandidates = emptyList(),
            dependencyResolutions = emptyList(),
            projectionChanges = ProjectionChangeSet(target, emptyList()),
            entityChanges = emptyList(),
            ruleSetVersion = book.ruleSetVersion,
        )
    }

    private fun receipt(command: RecordBudgetAdjustmentCommand): CommandReceipt = CommandReceipt(
        command.commandId,
        command.commandType,
        command.payloadHash,
        BookCommitId(id(20)),
        StableEntityReference(EntityType.BUDGET, command.adjustment.id.value),
        Instant.ofEpochSecond(2),
    )

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0L, value))

    private fun hash(value: Int): Hash256 = Hash256.fromBytes(ByteArray(32) { value.toByte() }).success()

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}
