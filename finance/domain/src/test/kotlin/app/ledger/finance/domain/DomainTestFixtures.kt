package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal fun stableId(index: Long): StableId = StableId.fromUuid(UUID(0L, index))

internal fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).success()

internal fun positive(minor: Long, currency: CurrencyCode): PositiveMoney = PositiveMoney.from(Money(minor, currency)).success()

internal fun hash(index: Int): Hash256 = Hash256.fromBytes(ByteArray(Hash256.BYTE_COUNT) { index.toByte() }).success()

internal fun localRevision(value: Long): LocalRevision = LocalRevision.of(value).success()

internal fun rowVersion(value: Long = 1L): RowVersion = RowVersion.of(value).success()

internal fun ruleSetVersion(value: Int = 1): RuleSetVersion = RuleSetVersion.of(value).success()

internal fun effective(epochSecond: Long = 0L): EffectiveTime = EffectiveTime.fromInstant(
    Instant.ofEpochSecond(epochSecond),
    ZoneId.of("Asia/Tokyo"),
)

internal fun book(localRevision: Long = 1L): Book = Book(
    id = BookId(stableId(1)),
    baseCurrency = currency("JPY"),
    defaultZoneId = ZoneId.of("Asia/Tokyo"),
    headCommitId = BookCommitId(stableId(2)),
    localRevision = localRevision(localRevision),
    valuationRevision = localRevision(1L),
    ruleSetVersion = ruleSetVersion(),
    createdAt = Instant.EPOCH,
    firstFinancialCommitAt = Instant.EPOCH,
    state = BookState.READY,
)

internal fun budgetAdjustment(): BudgetAdjustment = BudgetAdjustment(
    id = BudgetAdjustmentId(stableId(10)),
    month = java.time.YearMonth.of(2026, 8),
    scope = BudgetAdjustmentScope.TOTAL,
    categoryId = null,
    amountBaseMinor = 100L,
    kind = BudgetAdjustmentKind.INCREASE_AVAILABLE,
    createdCommitId = BookCommitId(stableId(11)),
    reversalOfId = null,
)

internal fun budgetCommand(): RecordBudgetAdjustmentCommand = RecordBudgetAdjustmentCommand(
    commandId = CommandId(stableId(12)),
    payloadHash = hash(12),
    adjustment = budgetAdjustment(),
)

internal fun budgetPlan(command: RecordBudgetAdjustmentCommand, book: Book): FinancialMutationPlan {
    val target = book.localRevision.next().success()
    return FinancialMutationPlan(
        commandId = command.commandId,
        commandType = command.commandType,
        payloadHash = command.payloadHash,
        expectedRevisionId = null,
        targetLocalRevision = target,
        commit = CommitDraft(
            id = BookCommitId(stableId(13)),
            kind = CommitKind.USER_MUTATION,
            parentIds = listOf(book.headCommitId),
            createdAt = Instant.ofEpochSecond(1),
            commandId = command.commandId,
            deviceInstanceId = DeviceInstanceId(stableId(14)),
            rootHash = hash(14),
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

internal fun planningSnapshot(book: Book): PlanningSnapshot = PlanningSnapshot(
    book = book,
    currentTransaction = null,
    currentRevision = null,
    dependencies = emptyList(),
    reversedApplyEntryIds = emptySet(),
    refundStatuses = emptyList(),
    budgetRevision = null,
    participants = emptyList(),
)

internal fun <T> DomainResult<T>.success(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> error("Expected success but was ${error.code}")
}
