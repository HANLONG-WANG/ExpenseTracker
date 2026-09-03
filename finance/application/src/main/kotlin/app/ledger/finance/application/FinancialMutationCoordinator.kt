@file:Suppress("NestedBlockDepth")

package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.FinancialMutationPlanValidator
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.ProjectionChange
import app.ledger.finance.domain.StableEntityReference
import java.util.concurrent.CopyOnWriteArraySet

interface FinancialMutationCoordinator {
    suspend fun execute(command: FinancialCommand): DomainResult<CommandReceipt>
}

enum class LedgerDataScope {
    ENTRY_REFERENCES,
    ACCOUNT_SUMMARIES,
    JOURNAL,
    BUDGET,
    PROJECTS_GOALS,
    CREDIT,
    INSTALLMENTS,
    LOANS,
    SETTLEMENT,
    REFUNDS,
    AUTOMATION,
    ANALYTICS,
    WIDGET,
    VAULT_CARD_METADATA,
    GLOBAL_RESET,
}

data class CommittedLedgerChange(
    val receipt: CommandReceipt,
    val bookId: StableId,
    val localRevision: LocalRevision,
    val valuationRevision: LocalRevision?,
    val scopes: Set<LedgerDataScope>,
    val entityIds: Set<StableEntityReference>,
)

fun interface FinancialCommitObserver {
    fun onCommitted(change: CommittedLedgerChange)
}

/** Process-local fan-out invoked only after a new atomic financial commit succeeds. */
object FinancialCommitObserverRegistry {
    private val observers = CopyOnWriteArraySet<FinancialCommitObserver>()

    fun register(observer: FinancialCommitObserver): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    internal fun notifyCommitted(change: CommittedLedgerChange) {
        observers.forEach { observer -> runCatching { observer.onCommitted(change) } }
    }
}

interface LedgerWriteGate {
    suspend fun <T> execute(block: suspend () -> T): T
}

fun interface FinancialWriteAvailability {
    fun isFinancialWriteAllowed(): Boolean
}

/** Rejects before planning and again under the serialized gate when the visible session is not Ready. */
class SessionAwareLedgerWriteGate(
    private val delegate: LedgerWriteGate,
    private val availability: FinancialWriteAvailability,
) : LedgerWriteGate {
    override suspend fun <T> execute(block: suspend () -> T): T {
        check(availability.isFinancialWriteAllowed()) { "financial write is unavailable outside a ready session" }
        return delegate.execute {
            check(availability.isFinancialWriteAllowed()) { "session changed before financial write" }
            block()
        }
    }
}

fun interface FinancialPlanningPort {
    fun plan(command: FinancialCommand, snapshot: PlanningSnapshot): DomainResult<FinancialMutationPlan>
}

interface FinancialPlanningSnapshotRepository {
    suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot>
}

interface CommandReceiptRepository {
    suspend fun find(commandId: CommandId): DomainResult<CommandReceipt?>
}

/** Adapter implementation must persist the complete plan and receipt in one database transaction. */
fun interface AtomicFinancialCommitRepository {
    suspend fun commit(command: FinancialCommand, plan: FinancialMutationPlan): DomainResult<CommandReceipt>
}

class DefaultFinancialMutationCoordinator(
    private val writeGate: LedgerWriteGate,
    private val receiptRepository: CommandReceiptRepository,
    private val snapshotRepository: FinancialPlanningSnapshotRepository,
    private val planner: FinancialPlanningPort,
    private val commitRepository: AtomicFinancialCommitRepository,
) : FinancialMutationCoordinator {
    override suspend fun execute(command: FinancialCommand): DomainResult<CommandReceipt> = writeGate.execute {
        if (CanonicalFinancialHash.command(command) != command.payloadHash) {
            return@execute DomainResult.Failure(DomainViolation.InvalidField("financialCommand.payloadHash"))
        }
        when (val existing = receiptRepository.find(command.commandId)) {
            is DomainResult.Failure -> existing
            is DomainResult.Success -> {
                val receipt = existing.value
                if (receipt != null) {
                    if (receipt.payloadHash == command.payloadHash && receipt.commandType == command.commandType) {
                        DomainResult.Success(receipt)
                    } else {
                        DomainResult.Failure(DomainViolation.DuplicateCommandPayloadMismatch)
                    }
                } else {
                    executeNew(command)
                }
            }
        }
    }

    private suspend fun executeNew(command: FinancialCommand): DomainResult<CommandReceipt> = when (
        val snapshot = snapshotRepository.load(command)
    ) {
        is DomainResult.Failure -> snapshot
        is DomainResult.Success -> when (val proposed = planner.plan(command, snapshot.value)) {
            is DomainResult.Failure -> proposed
            is DomainResult.Success -> when (
                val validated = FinancialMutationPlanValidator.validate(command, snapshot.value, proposed.value)
            ) {
                is DomainResult.Failure -> validated
                is DomainResult.Success -> when (val committed = commitRepository.commit(command, validated.value)) {
                    is DomainResult.Failure -> committed
                    is DomainResult.Success -> committed.also {
                        FinancialCommitObserverRegistry.notifyCommitted(
                            committedChange(it.value, snapshot.value, validated.value),
                        )
                    }
                }
            }
        }
    }

    private fun committedChange(
        receipt: CommandReceipt,
        snapshot: PlanningSnapshot,
        plan: FinancialMutationPlan,
    ): CommittedLedgerChange {
        val scopes = plan.projectionChanges.changes.flatMapTo(linkedSetOf()) { change ->
            when (change) {
                is ProjectionChange.CurrentTransaction -> setOf(LedgerDataScope.JOURNAL)
                is ProjectionChange.AccountFromDate -> setOf(LedgerDataScope.ACCOUNT_SUMMARIES, LedgerDataScope.JOURNAL)
                is ProjectionChange.BudgetFromMonth -> setOf(LedgerDataScope.BUDGET)
                is ProjectionChange.Project, is ProjectionChange.Goal -> setOf(LedgerDataScope.PROJECTS_GOALS)
                is ProjectionChange.Statement -> setOf(LedgerDataScope.CREDIT)
                is ProjectionChange.Installment -> setOf(LedgerDataScope.INSTALLMENTS)
                is ProjectionChange.Loan -> setOf(LedgerDataScope.LOANS)
                is ProjectionChange.Settlement -> setOf(LedgerDataScope.SETTLEMENT)
                is ProjectionChange.Refund -> setOf(LedgerDataScope.REFUNDS)
                is ProjectionChange.SearchAndMap -> setOf(LedgerDataScope.ANALYTICS)
                is ProjectionChange.Widget -> setOf(LedgerDataScope.WIDGET)
            }
        }
        receipt.primaryEntityId?.let { primary ->
            scopes += when (primary.type) {
                app.ledger.finance.domain.EntityType.ACCOUNT -> LedgerDataScope.ACCOUNT_SUMMARIES
                app.ledger.finance.domain.EntityType.BUDGET,
                app.ledger.finance.domain.EntityType.BUDGET_TEMPLATE,
                -> LedgerDataScope.BUDGET
                app.ledger.finance.domain.EntityType.PROJECT,
                app.ledger.finance.domain.EntityType.GOAL,
                -> LedgerDataScope.PROJECTS_GOALS
                app.ledger.finance.domain.EntityType.CREDIT_STATEMENT -> LedgerDataScope.CREDIT
                app.ledger.finance.domain.EntityType.INSTALLMENT_PLAN -> LedgerDataScope.INSTALLMENTS
                app.ledger.finance.domain.EntityType.LOAN -> LedgerDataScope.LOANS
                app.ledger.finance.domain.EntityType.SETTLEMENT_ACTIVITY,
                app.ledger.finance.domain.EntityType.PARTICIPANT,
                -> LedgerDataScope.SETTLEMENT
                app.ledger.finance.domain.EntityType.TRANSACTION -> LedgerDataScope.JOURNAL
                else -> LedgerDataScope.ENTRY_REFERENCES
            }
        }
        return CommittedLedgerChange(
            receipt = receipt,
            bookId = snapshot.book.id.value,
            localRevision = plan.targetLocalRevision,
            valuationRevision = snapshot.book.valuationRevision,
            scopes = scopes,
            entityIds = (plan.entityChanges.map { it.entity } + listOfNotNull(receipt.primaryEntityId)).toSet(),
        )
    }
}
