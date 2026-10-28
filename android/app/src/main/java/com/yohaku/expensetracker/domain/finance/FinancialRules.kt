package com.yohaku.expensetracker.domain.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable enum class FinanceMode { SINGLE, INSTALLMENT, REVOLVING, FIXED_LOAN, CREDIT_LINE }
@Serializable enum class RemainderPlacement { FIRST, LAST }
@Serializable enum class ChargeFrequency { DAILY, MONTHLY }
@Serializable enum class ChargeRounding { EACH_DAY, PERIOD_TOTAL }
@Serializable enum class BalanceBasis { EVENT_BALANCE, OPENING_BALANCE }
@Serializable enum class PaymentMethod { FIXED_PRINCIPAL, TOTAL_PAYMENT, BALANCE_TIERS }
@Serializable enum class TierBalanceDate { BILL_CLOSING, PAYMENT_DATE }
@Serializable enum class LoanMethod { EQUAL_PRINCIPAL, ANNUITY, INTEREST_ONLY, MANUAL }
@Serializable enum class AllocationOrder { FEES_FIRST, PRINCIPAL_FIRST }
@Serializable enum class LimitRecovery { PAYMENT_DATE, PLATFORM_CONFIRMATION, MANUAL }
@Serializable enum class FirstCharge { NORMAL, WAIVED }

/** Rates are decimal fractions (18% = "0.18"); amounts are account-currency minor units. */
@Serializable data class InterestRule(
    val annualRate: String? = null,
    val frequency: ChargeFrequency? = null,
    val balanceBasis: BalanceBasis? = null,
    val includeStart: Boolean? = null,
    val includeEnd: Boolean? = null,
    val yearDays: Int? = null,
    val rounding: ChargeRounding? = null,
    val roundingMode: String? = null,
    val firstCharge: FirstCharge? = null
) {
    fun issues(): List<String> = buildList {
        if (annualRate?.toBigDecimalOrNull()?.let { it >= BigDecimal.ZERO } != true) add("interest.annualRate")
        if (frequency == null) add("interest.frequency")
        if (balanceBasis == null) add("interest.balanceBasis")
        if (frequency == ChargeFrequency.DAILY) {
            if (includeStart == null) add("interest.includeStart")
            if (includeEnd == null) add("interest.includeEnd")
            if (yearDays == null || yearDays <= 0) add("interest.yearDays")
        }
        if (rounding == null) add("interest.rounding")
        if (roundingMode !in RoundingMode.entries.map { it.name }) add("interest.roundingMode")
        if (firstCharge == null) add("interest.firstCharge")
        if (frequency == ChargeFrequency.MONTHLY && rounding == ChargeRounding.EACH_DAY) add("interest.monthlyRounding")
    }
}

@Serializable data class PaymentTier(val upToMinor: Long?, val paymentMinor: Long)
@Serializable data class RevolvingRule(
    val interest: InterestRule = InterestRule(),
    val method: PaymentMethod? = null,
    val paymentMinor: Long? = null,
    val tiers: List<PaymentTier> = emptyList(),
    val tierPaymentIncludesFees: Boolean? = null,
    val allocation: AllocationOrder? = null,
    val automatic: Boolean? = null,
    val tierBalanceDate: TierBalanceDate? = null
) {
    fun issues(): List<String> = interest.issues() + buildList {
        if (method == null) add("revolving.method")
        if (method != PaymentMethod.BALANCE_TIERS && (paymentMinor == null || paymentMinor <= 0)) add("revolving.payment")
        if (method == PaymentMethod.BALANCE_TIERS) {
            if (tiers.isEmpty() || tiers.last().upToMinor != null || tiers.any { it.paymentMinor <= 0 } ||
                tiers.dropLast(1).any { it.upToMinor == null || it.upToMinor <= 0 } ||
                tiers.dropLast(1).zipWithNext().any { (a,b) -> a.upToMinor!! >= b.upToMinor!! }) add("revolving.tiers")
            if (tierPaymentIncludesFees == null) add("revolving.tierPaymentIncludesFees")
            if (tierBalanceDate == null) add("revolving.tierBalanceDate")
        }
        if (allocation == null) add("revolving.allocation")
    }
}

@Serializable data class InstallmentFee(val periods: Int, val feePerHundred: String)
@Serializable enum class InstallmentFeeMethod { TOTAL_AMOUNT, TOTAL_RATE, PER_HUNDRED_TABLE }
@Serializable data class InstallmentRule(
    val supportedPeriods: List<Int> = emptyList(),
    val feeMethod: InstallmentFeeMethod? = null,
    val totalFeeMinor: Long? = null,
    val totalRate: String? = null,
    val feeTable: List<InstallmentFee> = emptyList(),
    val principalRemainder: RemainderPlacement? = null,
    val feeRemainder: RemainderPlacement? = null,
    val roundingMode: String? = null
) {
    fun issues(): List<String> = buildList {
        if (supportedPeriods.isEmpty() || supportedPeriods.any { it <= 0 } || supportedPeriods.distinct().size != supportedPeriods.size) add("installment.supportedPeriods")
        if (feeMethod == null) add("installment.feeMethod")
        when (feeMethod) {
            InstallmentFeeMethod.TOTAL_AMOUNT -> if (totalFeeMinor == null || totalFeeMinor < 0) add("installment.totalFee")
            InstallmentFeeMethod.TOTAL_RATE -> if (totalRate?.toBigDecimalOrNull()?.let { it >= BigDecimal.ZERO } != true) add("installment.totalRate")
            InstallmentFeeMethod.PER_HUNDRED_TABLE -> if (feeTable.any { it.periods <= 0 || it.feePerHundred.toBigDecimalOrNull()?.let { n -> n >= BigDecimal.ZERO } != true } || supportedPeriods.any { p -> feeTable.count { it.periods == p } != 1 }) add("installment.feeTable")
            null -> Unit
        }
        if (principalRemainder == null) add("installment.principalRemainder")
        if (feeRemainder == null) add("installment.feeRemainder")
        if (roundingMode !in RoundingMode.entries.map { it.name }) add("installment.roundingMode")
    }
}

@Serializable data class BillingRule(
    val closingDay: Int? = null, val closingDayInCurrentBill: Boolean? = null,
    val paymentMonthOffset: Int? = null, val paymentDay: Int? = null,
    val paymentAccountId: String? = null
) {
    fun issues(): List<String> = buildList {
        if (closingDay == null || closingDay !in 1..31) add("billing.closingDay")
        if (closingDayInCurrentBill == null) add("billing.closingBoundary")
        if (paymentMonthOffset == null || paymentMonthOffset < 0) add("billing.paymentMonthOffset")
        if (paymentDay == null || paymentDay !in 1..31) add("billing.paymentDay")
        if (paymentAccountId.isNullOrBlank()) add("billing.paymentAccount")
        if (paymentMonthOffset==0&&closingDay!=null&&paymentDay!=null&&paymentDay<closingDay) add("billing.paymentOrder")
    }
}

@Serializable data class FinancialRuleVersion(
    val id: String, val bookId: String, val accountId: String, val mode: FinanceMode,
    val effectiveFrom: String, val zoneId: String,
    val billing: BillingRule? = null,
    val installment: InstallmentRule? = null,
    val revolving: RevolvingRule? = null,
    val loanMethod: LoanMethod? = null,
    val loanInterest: InterestRule? = null,
    val allocation: AllocationOrder? = null,
    // Historical input only. Operational recovery is versioned in the account credit-limit settings.
    val limitRecovery: LimitRecovery? = null,
    val unsupportedReason: String? = null,
    val loanPrincipalRemainder: RemainderPlacement? = null,
    val deadlines: List<ChangeDeadline> = emptyList(),
    val bonusPayments: List<BonusPaymentWindow> = emptyList()
) {
    fun issues(): List<String> = buildList {
        if (runCatching { LocalDate.parse(effectiveFrom) }.isFailure) add("rule.effectiveFrom")
        if (runCatching { ZoneId.of(zoneId) }.isFailure) add("rule.zoneId")
        if (deadlines.any { it.billId.isBlank() || it.changeType !in listOf(FinanceMode.INSTALLMENT,FinanceMode.REVOLVING) || runCatching { it.instant() }.isFailure } ||
            deadlines.map { it.billId to it.changeType }.distinct().size != deadlines.size) add("rule.deadlines")
        when (mode) {
            FinanceMode.SINGLE -> { addAll(billing?.issues() ?: listOf("billing"));addAll(bonusPaymentIssues(bonusPayments)) }
            FinanceMode.INSTALLMENT -> { addAll(billing?.issues() ?: listOf("billing")); addAll(installment?.issues() ?: listOf("installment")) }
            FinanceMode.REVOLVING -> { addAll(billing?.issues() ?: listOf("billing")); addAll(revolving?.issues() ?: listOf("revolving")); if(revolving!=null&&revolving.automatic==null)add("revolving.automatic") }
            FinanceMode.FIXED_LOAN -> {
                if (loanMethod == null) add("loan.method")
                if (loanMethod == LoanMethod.EQUAL_PRINCIPAL && loanPrincipalRemainder == null) add("loan.principalRemainder")
                if (loanMethod != LoanMethod.MANUAL) addAll(loanInterest?.issues() ?: listOf("loan.interest"))
                if (allocation == null) add("loan.allocation")
            }
            FinanceMode.CREDIT_LINE -> addAll(revolving?.issues() ?: listOf("revolving"))
        }
    }
}

/** Deadline belongs to a bill and a change type, with an explicit financial timezone. */
@Serializable data class ChangeDeadline(val billId: String, val changeType: FinanceMode, val date: String, val time: String, val zoneId: String) {
    fun instant() = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.of(zoneId)).toInstant()
}

fun dayInMonth(month: YearMonth, day: Int): LocalDate {
    require(day in 1..31)
    return month.atDay(minOf(day, month.lengthOfMonth()))
}

data class BillDates(val closingDate: LocalDate, val paymentDate: LocalDate, val monthEndAdjusted: Boolean)
fun billingDates(transactionDate: LocalDate, rule: BillingRule, actualPaymentDate: LocalDate? = null): BillDates {
    require(rule.issues().isEmpty())
    var month = YearMonth.from(transactionDate)
    val boundary = dayInMonth(month, rule.closingDay!!)
    if (transactionDate > boundary || transactionDate == boundary && rule.closingDayInCurrentBill == false) month = month.plusMonths(1)
    val paymentMonth = month.plusMonths(rule.paymentMonthOffset!!.toLong())
    return BillDates(dayInMonth(month, rule.closingDay), actualPaymentDate ?: dayInMonth(paymentMonth, rule.paymentDay!!),
        rule.closingDay > month.lengthOfMonth() || rule.paymentDay!! > paymentMonth.lengthOfMonth())
}

sealed interface RuleReadiness {
    data object Complete: RuleReadiness
    data class Incomplete(val fields: List<String>): RuleReadiness
    data class Unsupported(val reason: String): RuleReadiness
}

fun FinancialRuleVersion.readiness(): RuleReadiness {
    if (!unsupportedReason.isNullOrBlank()) return RuleReadiness.Unsupported(unsupportedReason)
    val missing = issues()
    return if (missing.isEmpty()) RuleReadiness.Complete else RuleReadiness.Incomplete(missing)
}
