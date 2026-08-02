package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.FinancialMutationPlanValidator
import app.ledger.finance.domain.PlanningSnapshot

interface FinancialMutationCoordinator {
    suspend fun execute(command: FinancialCommand): DomainResult<CommandReceipt>
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
                is DomainResult.Success -> commitRepository.commit(command, validated.value)
            }
        }
    }
}
