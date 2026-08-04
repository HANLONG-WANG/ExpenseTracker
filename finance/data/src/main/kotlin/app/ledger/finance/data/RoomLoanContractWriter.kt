@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.LoanPrepaymentPolicy
import java.math.BigDecimal
import java.math.RoundingMode

internal class RoomLoanContractWriter {
    fun write(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.loanContractMutations.forEach { mutation ->
            val contract = mutation.contract
            val contractId = if (mutation.expectedLastCommitId == null) {
                database.allocateInternalId("loan_contract", contract.id.value).also { id ->
                    database.execSQL(
                        "INSERT INTO loan_contract(id,uid,display_account_id,name,lender,currency_code,disbursement_date,status,last_commit_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            id,
                            contract.id.value.bytes,
                            database.requireInternalId("user_account", contract.displayAccountId.value),
                            contract.name,
                            contract.lender,
                            contract.currency.value,
                            contract.disbursementDate.toStorageInt(),
                            contract.status.ordinal,
                            database.commitId(contract.lastCommitId),
                        ),
                    )
                }
            } else {
                database.requireInternalId("loan_contract", contract.id.value).also { id ->
                    val lender = contract.lender
                    val changed = database.compileStatement(
                        "UPDATE loan_contract SET name=?,lender=?,status=?,last_commit_id=? WHERE id=? AND last_commit_id=?",
                    ).apply {
                        bindString(1, contract.name)
                        if (lender == null) bindNull(2) else bindString(2, lender)
                        bindLong(3, contract.status.ordinal.toLong())
                        bindLong(4, database.commitId(contract.lastCommitId))
                        bindLong(5, id)
                        bindLong(6, database.commitId(requireNotNull(mutation.expectedLastCommitId)))
                    }.executeUpdateDelete()
                    if (changed != 1) abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
                }
            }
            mutation.tranches.forEach { trancheMutation ->
                val tranche = trancheMutation.tranche
                val trancheId = if (trancheMutation.expectedTermsRevisionId == null) {
                    database.allocateInternalId("loan_tranche", tranche.id.value).also { id ->
                        database.execSQL(
                            "INSERT INTO loan_tranche(id,uid,contract_id,ledger_account_id,name,original_principal_minor,status) " +
                                "VALUES(?,?,?,?,?,?,?)",
                            arrayOf<Any>(
                                id,
                                tranche.id.value.bytes,
                                contractId,
                                database.requireInternalId("ledger_account", tranche.ledgerAccountId.value),
                                tranche.name,
                                tranche.originalPrincipalMinor,
                                tranche.status.ordinal,
                            ),
                        )
                    }
                } else {
                    database.requireInternalId("loan_tranche", tranche.id.value).also { id ->
                        database.execSQL(
                            "UPDATE loan_tranche SET name=?,status=? WHERE id=? AND contract_id=?",
                            arrayOf<Any>(tranche.name, tranche.status.ordinal, id, contractId),
                        )
                    }
                }
                writeTerms(database, trancheId, trancheMutation)
            }
        }
    }

    private fun writeTerms(
        database: SupportSQLiteDatabase,
        trancheId: Long,
        mutation: app.ledger.finance.domain.LoanTrancheMutation,
    ) {
        val terms = mutation.termsRevision
        val termsId = database.allocateInternalId("loan_terms_revision", terms.id.value)
        database.execSQL(
            "INSERT INTO loan_terms_revision(id,uid,tranche_id,revision_no,repayment_method,rate_type,payment_frequency," +
                "start_date,end_date,rounding_mode,prepayment_policy,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                termsId,
                terms.id.value.bytes,
                trancheId,
                terms.revisionNumber,
                terms.repaymentMethod.ordinal,
                terms.rateType.ordinal,
                terms.paymentFrequency.ordinal,
                terms.startDate.toStorageInt(),
                terms.endDate.toStorageInt(),
                terms.roundingMode.ordinal,
                LoanPrepaymentCodec.encode(
                    terms.prepaymentPolicy,
                    terms.prepaymentStrategy,
                    terms.penaltyRate?.annualDecimal,
                ),
                database.commitId(terms.createdCommitId),
            ),
        )
        terms.ratePeriods.forEach { rate ->
            database.execSQL(
                "INSERT INTO loan_rate_period(id,terms_revision_id,effective_from,effective_to,annual_rate_decimal,benchmark,margin_decimal) " +
                    "VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    database.nextLoanRatePeriodId(),
                    termsId,
                    rate.effectiveFrom.toStorageInt(),
                    rate.effectiveTo?.toStorageInt(),
                    rate.annualRate.annualDecimal.toPlainString(),
                    rate.benchmark,
                    rate.margin?.annualDecimal?.toPlainString(),
                ),
            )
        }
        val schedule = mutation.scheduleRevision
        val scheduleId = database.allocateInternalId("loan_schedule_revision", schedule.id.value)
        database.execSQL(
            "INSERT INTO loan_schedule_revision(id,uid,tranche_id,revision_no,terms_revision_id,reason,generated_at,created_commit_id) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                scheduleId,
                schedule.id.value.bytes,
                trancheId,
                schedule.revisionNumber,
                termsId,
                schedule.reason.ordinal,
                schedule.generatedAt.toStorageEpochMillis(),
                database.commitId(schedule.createdCommitId),
            ),
        )
        schedule.items.forEach { item ->
            database.execSQL(
                "INSERT INTO loan_schedule_item(id,schedule_revision_id,installment_no,planned_date,principal_minor,interest_minor," +
                    "fee_minor,remaining_principal_minor) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    item.id.value.internalId(),
                    scheduleId,
                    item.installmentNumber,
                    item.plannedDate.toStorageInt(),
                    item.principalMinor,
                    item.interestMinor,
                    item.feeMinor,
                    item.remainingPrincipalMinor,
                ),
            )
        }
    }
}

internal object LoanPrepaymentCodec {
    private const val RATE_SCALE = 6
    private const val POLICY_FACTOR = 10L
    private const val STRATEGY_FACTOR = 100L

    fun encode(
        policy: LoanPrepaymentPolicy,
        strategy: app.ledger.finance.domain.PrepaymentRecalculationStrategy,
        rate: BigDecimal?,
    ): Long {
        val rateMicros = (rate ?: BigDecimal.ZERO)
            .setScale(RATE_SCALE, RoundingMode.UNNECESSARY)
            .movePointRight(RATE_SCALE)
            .longValueExact()
        require(rateMicros >= 0L)
        return Math.addExact(
            policy.ordinal.toLong(),
            Math.addExact(
                Math.multiplyExact(strategy.ordinal.toLong(), POLICY_FACTOR),
                Math.multiplyExact(rateMicros, STRATEGY_FACTOR),
            ),
        )
    }

    fun decode(value: Long): DecodedPrepayment {
        require(value >= 0L)
        val policy = LoanPrepaymentPolicy.entries[(value % POLICY_FACTOR).toInt()]
        val strategy = app.ledger.finance.domain.PrepaymentRecalculationStrategy.entries[
            ((value / POLICY_FACTOR) % POLICY_FACTOR).toInt(),
        ]
        val rateMicros = value / STRATEGY_FACTOR
        val rate = if (policy == LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY) {
            app.ledger.finance.domain.InterestRate.of(BigDecimal.valueOf(rateMicros, RATE_SCALE)).valueOrAbort()
        } else {
            null
        }
        return DecodedPrepayment(policy, strategy, rate)
    }
}

internal data class DecodedPrepayment(
    val policy: LoanPrepaymentPolicy,
    val strategy: app.ledger.finance.domain.PrepaymentRecalculationStrategy,
    val penaltyRate: app.ledger.finance.domain.InterestRate?,
)

private fun SupportSQLiteDatabase.nextLoanRatePeriodId(): Long {
    val maximum = queryOne("SELECT COALESCE(MAX(id),0) FROM loan_rate_period") { it.getLong(0) } ?: 0L
    return try {
        Math.addExact(maximum, 1L)
    } catch (_: ArithmeticException) {
        abort(FinanceDataError.NumericRangeExceeded)
    }
}
