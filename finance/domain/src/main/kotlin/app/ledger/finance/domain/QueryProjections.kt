package app.ledger.finance.domain

import app.ledger.core.money.CurrencyCode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class CurrentTransactionProjection(
    val transactionId: TransactionId,
    val kind: TransactionKind,
    val state: TransactionLifecycleState,
    val currentRevisionId: TransactionRevisionId,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val primaryAccountId: UserAccountId?,
    val secondaryAccountId: UserAccountId?,
    val cardId: PaymentCardId?,
    val categoryId: CategoryId?,
    val merchantId: MerchantId?,
    val projectId: ProjectId?,
    val goalId: GoalId?,
    val settlementActivityId: SettlementActivityId?,
    val payerParticipantId: ParticipantId?,
    val inputAmountMinor: Long,
    val inputCurrency: CurrencyCode,
    val accountAmountMinor: Long,
    val accountCurrency: CurrencyCode,
    val economicBaseMinor: Long?,
    val notePreview: String?,
    val hasAttachment: Boolean,
    val hasLocation: Boolean,
    val isRefund: Boolean,
    val isRefunded: Boolean,
    val hasInstallment: Boolean,
    val source: TransactionSource,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class AccountValuationProjection(
    val accountId: UserAccountId,
    val balanceMinor: Long,
    val accountCurrency: CurrencyCode,
    val currentBaseValueMinor: Long,
    val baseCurrency: CurrencyCode,
    val rate: BigDecimal,
    val rateQuotedAt: Instant?,
    val asOfLocalRevision: LocalRevision,
    val asOfValuationRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class AccountBalanceDailyProjection(
    val accountId: UserAccountId,
    val localDate: LocalDate,
    val openingMinor: Long,
    val inflowMinor: Long,
    val outflowMinor: Long,
    val closingMinor: Long,
    val currency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class BudgetUsageProjection(
    val month: YearMonth,
    val categoryId: CategoryId?,
    val baseBudgetMinor: Long,
    val rolloverMinor: Long,
    val adjustmentMinor: Long,
    val usedMinor: Long,
    val remainingMinor: Long,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class BudgetFutureReservationProjection(
    val month: YearMonth,
    val recurrenceSeriesId: RecurrenceSeriesId,
    val occurrenceDate: LocalDate,
    val reservedBaseMinor: Long,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class ProjectUsageProjection(
    val projectId: ProjectId,
    val budgetBaseMinor: Long,
    val usedBaseMinor: Long,
    val restoredBaseMinor: Long,
    val remainingBaseMinor: Long,
    val cashInflowBaseMinor: Long,
    val cashOutflowBaseMinor: Long,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class InstallmentProgressProjection(
    val planId: InstallmentPlanId,
    val principalMinor: Long,
    val postedPrincipalMinor: Long,
    val unpostedCommittedPrincipalMinor: Long,
    val feesMinor: Long,
    val nextStatementDate: LocalDate?,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class LoanFutureCashflowProjection(
    val contractId: LoanContractId,
    val trancheId: LoanTrancheId,
    val plannedDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val currency: CurrencyCode,
    val scheduleRevisionId: LoanScheduleRevisionId,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class TransactionAmountRange(
    val minimumAccountMinor: Long?,
    val maximumAccountMinor: Long?,
    val currency: CurrencyCode?,
) {
    init {
        require(minimumAccountMinor != null || maximumAccountMinor != null)
        require(
            minimumAccountMinor == null || maximumAccountMinor == null ||
                maximumAccountMinor >= minimumAccountMinor,
        )
    }
}

data class GeoRadiusFilter(
    val center: GeoPoint,
    val radiusMeters: Int,
) {
    init {
        require(radiusMeters > 0)
    }
}

data class TransactionFilter(
    val occurredFrom: Instant? = null,
    val occurredThrough: Instant? = null,
    val createdFrom: Instant? = null,
    val createdThrough: Instant? = null,
    val modifiedFrom: Instant? = null,
    val modifiedThrough: Instant? = null,
    val kinds: Set<TransactionKind> = emptySet(),
    val accountIds: Set<UserAccountId> = emptySet(),
    val cardIds: Set<PaymentCardId> = emptySet(),
    val categoryIds: Set<CategoryId> = emptySet(),
    val merchantIds: Set<MerchantId> = emptySet(),
    val projectIds: Set<ProjectId> = emptySet(),
    val settlementActivityIds: Set<SettlementActivityId> = emptySet(),
    val participantIds: Set<ParticipantId> = emptySet(),
    val currencies: Set<CurrencyCode> = emptySet(),
    val statisticalNatures: Set<StatisticalNature> = emptySet(),
    val amountRange: TransactionAmountRange? = null,
    val geoRadius: GeoRadiusFilter? = null,
    val hasAttachment: Boolean? = null,
    val isRefund: Boolean? = null,
    val hasInstallment: Boolean? = null,
    val includedInBudget: Boolean? = null,
    val generatedByRecurrence: Boolean? = null,
    val sources: Set<TransactionSource> = emptySet(),
    val lifecycleStates: Set<TransactionLifecycleState> = emptySet(),
    val searchText: String? = null,
) {
    init {
        require(occurredFrom == null || occurredThrough == null || occurredThrough >= occurredFrom)
        require(createdFrom == null || createdThrough == null || createdThrough >= createdFrom)
        require(modifiedFrom == null || modifiedThrough == null || modifiedThrough >= modifiedFrom)
    }
}

data class WidgetBookSnapshot(
    val coreNetFinancialAssetsBaseMinor: Long,
    val adjustedNetFinancialPositionBaseMinor: Long,
    val baseCurrency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
    val asOfValuationRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class WidgetAccountSnapshot(
    val accountId: UserAccountId,
    val balanceMinor: Long,
    val currency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class WidgetCreditSnapshot(
    val accountId: UserAccountId,
    val debtMinor: Long,
    val availableLimitMinor: Long?,
    val currency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class WidgetGoalSnapshot(
    val goalId: GoalId,
    val balanceMinor: Long,
    val targetMinor: Long,
    val currency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}
