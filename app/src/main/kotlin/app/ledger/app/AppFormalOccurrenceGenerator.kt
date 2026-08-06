@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions", "ReturnCount", "MaxLineLength")

package app.ledger.app

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.MoneyExpressionEvaluator
import app.ledger.finance.application.BlueprintView
import app.ledger.finance.application.CreditApplicationPort
import app.ledger.finance.application.CreditPaymentContext
import app.ledger.finance.application.CreditTransactionMutationIds
import app.ledger.finance.application.FormalOccurrenceGenerator
import app.ledger.finance.application.FormalOccurrenceRequest
import app.ledger.finance.application.LoanApplicationPort
import app.ledger.finance.application.LoanComponentAllocationDraft
import app.ledger.finance.application.LoanComponentAmountDraft
import app.ledger.finance.application.LoanMutationIds
import app.ledger.finance.application.LoanPaymentAmountsDraft
import app.ledger.finance.application.LoanTermsDraft
import app.ledger.finance.application.LoanTrancheDraft
import app.ledger.finance.application.LoanTrancheMutationIds
import app.ledger.finance.application.LoanTransactionContext
import app.ledger.finance.application.LoanTransactionIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryLocationDraft
import app.ledger.finance.application.OrdinaryLocationProvider
import app.ledger.finance.application.OrdinaryTransactionEntryPort
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.RecordCreditPaymentRequest
import app.ledger.finance.application.RecordLoanPaymentRequest
import app.ledger.finance.application.SaveLoanContractRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.CreditPaymentSelection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.ScheduleRevisionReason
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionSource
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

internal class AppFormalOccurrenceGenerator(
    private val ordinary: OrdinaryTransactionEntryPort,
    private val credit: CreditApplicationPort,
    private val loan: LoanApplicationPort,
) : FormalOccurrenceGenerator {
    private val currencies = JvmLegalTenderCurrencyCatalog.create()
    private val evaluator = MoneyExpressionEvaluator()

    override suspend fun generate(request: FormalOccurrenceRequest): DomainResult<StableId> = when (request.blueprint.targetKind) {
        TransactionKind.EXPENSE, TransactionKind.INCOME -> ordinary(request)
        TransactionKind.CREDIT_PAYMENT -> credit(request)
        TransactionKind.LOAN_PAYMENT -> loan(request)
        else -> DomainResult.Failure(DomainViolation.InvalidStateTransition("recurrence.unsupportedFormalKind"))
    }

    private suspend fun ordinary(request: FormalOccurrenceRequest): DomainResult<StableId> {
        val sourceOccurrenceId = request.occurrenceId
        val snapshot = when (val result = ordinary.snapshot(request.bookId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val blueprint = request.blueprint
        val categoryId = blueprint.categoryId ?: return invalid("recurrence.category")
        val accountId = blueprint.primaryAccountId ?: return invalid("recurrence.account")
        val account = snapshot.references.accounts.singleOrNull { it.id == accountId && it.status == EntityStatus.ACTIVE }
            ?: return invalid("recurrence.account")
        val currency = blueprint.currency ?: account.currency
        if (currency != account.currency || currency != snapshot.references.baseCurrency) return invalid("recurrence.fxEvidence")
        val expression = blueprint.amountExpression ?: return invalid("recurrence.amount")
        val metadata = currencies.find(currency) ?: return invalid("recurrence.currency")
        val evaluated = when (val result = evaluator.evaluate(expression, Locale.ROOT, metadata)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val transactionId = id(request, "transaction")
        val fixedPlace = blueprint.fixedPlaceId?.let { placeId -> snapshot.references.places.singleOrNull { it.id == placeId && it.status == EntityStatus.ACTIVE } }
        if (blueprint.fixedPlaceId != null && fixedPlace == null) return invalid("recurrence.fixedPlace")
        if (blueprint.settlementActivityId != null) return invalid("recurrence.settlementRequiresConfirmation")
        val locationId = fixedPlace?.let { id(request, "location") }
        val requestIds = OrdinaryTransactionWriteIds(
            request.bookId,
            id(request, "command"),
            transactionId,
            id(request, "revision"),
            id(request, "commit"),
            id(request, "device"),
            List(FACT_ID_RESERVE) { id(request, "fact-$it") },
            emptyList(),
        )
        val write = OrdinaryTransactionWriteRequest(
            requestIds,
            null,
            if (blueprint.targetKind == TransactionKind.EXPENSE) OrdinaryDirection.EXPENSE else OrdinaryDirection.INCOME,
            categoryId,
            OrdinaryAmountDraft(expression, evaluated.roundedMoney.minor, currency, evaluated.roundedMoney.minor, evaluated.roundedMoney.minor),
            accountId,
            blueprint.cardId,
            blueprint.merchantId,
            request.occurrenceInstant,
            request.zoneId,
            request.localDate,
            blueprint.projectId,
            blueprint.goalId,
            null,
            emptyList(),
            locationId,
            fixedPlace?.let { place -> OrdinaryLocationDraft(requireNotNull(locationId), place.latitudeE7, place.longitudeE7, null, request.occurrenceInstant, OrdinaryLocationProvider.MANUAL, place.id) },
            blueprint.noteTemplate,
            emptyList(),
            TransactionSource.RECURRENCE_AUTO,
            sourceOccurrenceId,
            request.occurrenceInstant,
        )
        return ordinary.submit(write).mapReceipt(transactionId)
    }

    private suspend fun credit(request: FormalOccurrenceRequest): DomainResult<StableId> {
        val sourceOccurrenceId = request.occurrenceId
        val snapshot = when (val result = credit.snapshot(request.bookId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val account = snapshot.accounts.singleOrNull { it.id == request.blueprint.primaryAccountId }
            ?: return invalid("recurrence.creditAccount")
        val statement = account.statements.filter { it.officialAmountMinor != null && it.remainingAmountMinor > 0L }.minByOrNull { it.dueDate }
            ?: return invalid("recurrence.officialStatement")
        val proposal = when (val result = credit.proposeAutoPayment(request.bookId, statement.id, sourceOccurrenceId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        if (!proposal.eligibility.eligible) return invalid("recurrence.creditEligibility")
        val payment = snapshot.paymentAccounts.singleOrNull { it.id == proposal.defaultPaymentAccountId && it.active }
            ?: return invalid("recurrence.paymentAccount")
        if (payment.currency != proposal.currency || proposal.currency != snapshot.baseCurrency) return invalid("recurrence.fxEvidence")
        val transactionId = id(request, "transaction")
        val ids = CreditTransactionMutationIds(
            request.bookId,
            CommandId(id(request, "command")),
            transactionId,
            id(request, "revision"),
            id(request, "commit"),
            id(request, "device"),
            List(CREDIT_FACT_ID_RESERVE) { id(request, "fact-$it") },
            emptyList(),
        )
        return credit.recordPayment(
            RecordCreditPaymentRequest(
                ids,
                CreditPaymentContext(request.occurrenceInstant, request.zoneId, request.localDate, proposal.amountMinor.toString(), request.blueprint.noteTemplate, request.occurrenceInstant),
                SpecializedAccountAmountDraft(payment.id, proposal.amountMinor, proposal.amountMinor, null),
                SpecializedAccountAmountDraft(account.id, proposal.amountMinor, proposal.amountMinor, null),
                CreditPaymentSelection.EarliestUnpaid,
                sourceOccurrenceId,
                AutoGenerationMode.FORMAL_TRANSACTION,
            ),
        ).mapReceipt(transactionId)
    }

    private suspend fun loan(request: FormalOccurrenceRequest): DomainResult<StableId> {
        val sourceOccurrenceId = request.occurrenceId
        val snapshot = when (val result = loan.snapshot(request.bookId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val contract = snapshot.contracts.singleOrNull { it.displayAccountId == request.blueprint.primaryAccountId && it.status == LoanStatus.ACTIVE }
            ?: return invalid("recurrence.loanContract")
        val payment = snapshot.paymentAccounts.singleOrNull { it.id == request.blueprint.secondaryAccountId && it.active }
            ?: return invalid("recurrence.paymentAccount")
        if (payment.currency != contract.currency || contract.currency != snapshot.baseCurrency) return invalid("recurrence.fxEvidence")
        val tranche = contract.tranches.firstOrNull { it.status == LoanStatus.ACTIVE && it.remainingPrincipalMinor > 0L }
            ?: return invalid("recurrence.loanTranche")
        val item = tranche.schedule.firstOrNull { line ->
            line.plannedDate <= request.localDate &&
                (line.actualPrincipalMinor < line.principalMinor || line.actualInterestMinor < line.interestMinor || line.actualFeeMinor < line.feeMinor)
        } ?: return invalid("recurrence.loanSchedule")
        val principal = Math.subtractExact(item.principalMinor, item.actualPrincipalMinor)
        val interest = Math.subtractExact(item.interestMinor, item.actualInterestMinor)
        val fee = Math.subtractExact(item.feeMinor, item.actualFeeMinor)
        val total = listOf(principal, interest, fee).fold(0L, Math::addExact)
        if (total <= 0L || principal > tranche.remainingPrincipalMinor) return invalid("recurrence.loanAmount")
        val remaining = Math.subtractExact(tranche.remainingPrincipalMinor, principal)
        val futureCount = if (remaining == 0L) 0 else tranche.schedule.count { it.plannedDate > item.plannedDate }.coerceAtLeast(1)
        val trancheIds = LoanTrancheMutationIds(
            tranche.id,
            id(request, "loan-terms"),
            id(request, "loan-schedule"),
            List(futureCount) { id(request, "loan-schedule-item-$it") },
        )
        val firstFuture = tranche.schedule.firstOrNull { it.plannedDate > item.plannedDate }?.plannedDate ?: request.localDate.plusMonths(1)
        val terms = LoanTermsDraft(
            tranche.repaymentMethod,
            tranche.rateType,
            tranche.paymentFrequency,
            tranche.ratePeriods.first().effectiveFrom,
            tranche.ratePeriods.last().effectiveTo ?: tranche.schedule.last().plannedDate,
            futureCount.coerceAtLeast(1),
            firstFuture,
            tranche.roundingMode,
            tranche.prepaymentPolicy,
            tranche.prepaymentStrategy,
            tranche.penaltyRate,
            tranche.ratePeriods,
            feePerPaymentMinor = tranche.schedule.firstOrNull { it.plannedDate > item.plannedDate }?.feeMinor ?: 0L,
        )
        val mutation = SaveLoanContractRequest(
            LoanMutationIds(request.bookId, CommandId(id(request, "command")), id(request, "commit"), id(request, "device"), contract.id, listOf(trancheIds)),
            contract.displayAccountId,
            contract.name,
            contract.lender,
            contract.currency,
            contract.disbursementDate,
            if (remaining == 0L && contract.tranches.size == 1) LoanStatus.PAID_OFF else contract.status,
            contract.lastCommitId,
            listOf(
                LoanTrancheDraft(
                    trancheIds, tranche.ledgerAccountId, tranche.name, tranche.originalPrincipalMinor, remaining,
                    if (remaining == 0L) LoanStatus.PAID_OFF else tranche.status, tranche.currentTermsRevisionId,
                    tranche.termsRevisionNumber + 1, tranche.scheduleRevisionNumber + 1, ScheduleRevisionReason.ACTUAL_VARIANCE, terms,
                ),
            ),
            request.occurrenceInstant,
        )
        val transactionId = id(request, "transaction")
        val amount = { value: Long -> value.takeIf { it > 0L }?.let { LoanComponentAmountDraft(it, it, null) } }
        val components = LoanPaymentAmountsDraft(amount(principal), amount(interest), amount(fee), null)
        val allocations = buildList {
            if (principal > 0L) add(LoanComponentAllocationDraft(tranche.id, null, LoanPaymentComponent.PRINCIPAL, principal, principal))
            if (interest > 0L) add(LoanComponentAllocationDraft(tranche.id, null, LoanPaymentComponent.INTEREST, interest, interest))
            if (fee > 0L) add(LoanComponentAllocationDraft(tranche.id, null, LoanPaymentComponent.FEE, fee, fee))
        }
        return loan.recordPayment(
            RecordLoanPaymentRequest(
                mutation,
                LoanTransactionIds(transactionId, id(request, "revision"), List(LOAN_FACT_ID_RESERVE) { id(request, "fact-$it") }, emptyList()),
                LoanTransactionContext(request.occurrenceInstant, request.zoneId, request.localDate, request.blueprint.noteTemplate, total.toString()),
                SpecializedAccountAmountDraft(payment.id, total, total, null),
                components,
                allocations,
                sourceOccurrenceId,
            ),
        ).mapReceipt(transactionId)
    }

    private fun id(request: FormalOccurrenceRequest, label: String): StableId {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("p23|${request.bookId}|${request.occurrenceId}|$label".toByteArray(Charsets.UTF_8))
            .copyOf(StableId.BYTE_COUNT)
        return requireNotNull(StableId.fromBytes(bytes).getOrNull())
    }

    private fun invalid(field: String): DomainResult.Failure = DomainResult.Failure(DomainViolation.InvalidField(field))

    private fun DomainResult<app.ledger.finance.domain.CommandReceipt>.mapReceipt(fallback: StableId): DomainResult<StableId> = when (this) {
        is DomainResult.Success -> DomainResult.Success(value.primaryEntityId?.stableId ?: fallback)
        is DomainResult.Failure -> this
    }

    private companion object {
        const val FACT_ID_RESERVE = 256
        const val CREDIT_FACT_ID_RESERVE = 96
        const val LOAN_FACT_ID_RESERVE = 128
    }
}
