@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "CyclomaticComplexMethod")

package app.ledger.finance.data

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.AutomationMutationIds
import app.ledger.finance.application.BlueprintDraft
import app.ledger.finance.application.BudgetCategoryLimitDraft
import app.ledger.finance.application.BudgetMutationIds
import app.ledger.finance.application.CardDraft
import app.ledger.finance.application.CategoryDraft
import app.ledger.finance.application.CreditMutationIds
import app.ledger.finance.application.CreditStatementMutationIds
import app.ledger.finance.application.FormalOccurrenceGenerator
import app.ledger.finance.application.GoalDraft
import app.ledger.finance.application.ImportFinancialError
import app.ledger.finance.application.InstallmentMutationIds
import app.ledger.finance.application.InstallmentTermsDraft
import app.ledger.finance.application.LoanMutationIds
import app.ledger.finance.application.LoanTermsDraft
import app.ledger.finance.application.LoanTrancheDraft
import app.ledger.finance.application.LoanTrancheMutationIds
import app.ledger.finance.application.MerchantDraft
import app.ledger.finance.application.OrdinaryLocationDraft
import app.ledger.finance.application.OrdinaryLocationProvider
import app.ledger.finance.application.PlaceDraft
import app.ledger.finance.application.ProjectDraft
import app.ledger.finance.application.RecurrenceSeriesDraft
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.SaveBlueprintRequest
import app.ledger.finance.application.SaveBudgetMonthRequest
import app.ledger.finance.application.SaveCreditProfileRequest
import app.ledger.finance.application.SaveCreditStatementRequest
import app.ledger.finance.application.SaveInstallmentPlanRequest
import app.ledger.finance.application.SaveLoanContractRequest
import app.ledger.finance.application.SaveRecurrenceRequest
import app.ledger.finance.application.SaveSettlementActivityRequest
import app.ledger.finance.application.SettlementMutationIds
import app.ledger.finance.application.SettlementParticipantDraft
import app.ledger.finance.application.StructuredImportEntityType
import app.ledger.finance.application.StructuredImportRow
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentPrepaymentPolicy
import app.ledger.finance.domain.InstallmentRefundPolicy
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.ScheduleRevisionReason
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.UserAccountType
import app.ledger.finance.domain.WeekendAdjustment
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

internal data class StructuredAppliedCommit(val sourceRowNumber: Long, val commitId: StableId)

/** Converts redacted canonical workbook values to the same typed application requests used by editors. */
internal class StructuredImportRowApplier(
    context: android.content.Context,
    private val bookId: StableId,
    private val operationId: StableId,
    keyProvider: app.ledger.core.security.DeviceLedgerKeyProvider,
    databaseName: String,
) {
    private val databaseAccess = OfflineSelectedLedgerDatabaseAccess(context, keyProvider, databaseName)
    private val references = SecureRoomReferenceDataManagementPort(databaseAccess)
    private val credit = SecureRoomCreditApplicationPort(databaseAccess)
    private val installment = SecureRoomInstallmentApplicationPort(databaseAccess)
    private val loan = SecureRoomLoanApplicationPort(databaseAccess)
    private val budget = SecureRoomBudgetApplicationPort(databaseAccess)
    private val settlement = SecureRoomSettlementApplicationPort(databaseAccess)
    private val automation = SecureRoomAutomationApplicationPort(
        databaseAccess,
        FormalOccurrenceGenerator { DomainResult.Failure(ImportFinancialError.PageSequenceInvalid) },
    )

    suspend fun apply(row: StructuredImportRow): DomainResult<List<StructuredAppliedCommit>> = try {
        val values = Values(row.values.entries)
        val commits = when (row.entityType) {
            StructuredImportEntityType.ACCOUNT -> listOf(applyAccount(row.sourceRowNumber, values))
            StructuredImportEntityType.CARD -> listOf(applyCard(row.sourceRowNumber, values))
            StructuredImportEntityType.CATEGORY -> listOf(applyCategory(row.sourceRowNumber, values))
            StructuredImportEntityType.MERCHANT -> listOf(applyMerchant(row.sourceRowNumber, values))
            StructuredImportEntityType.PLACE -> listOf(applyPlace(row.sourceRowNumber, values))
            StructuredImportEntityType.GOAL -> listOf(applyGoal(row.sourceRowNumber, values))
            StructuredImportEntityType.PROJECT -> listOf(applyProject(row.sourceRowNumber, values))
            StructuredImportEntityType.SETTLEMENT_ACTIVITY -> listOf(applySettlement(row.sourceRowNumber, values))
            StructuredImportEntityType.LOCATION -> listOf(applyLocation(row.sourceRowNumber, values))
            StructuredImportEntityType.RECURRENCE -> applyRecurrence(row.sourceRowNumber, values)
            StructuredImportEntityType.CREDIT_STATEMENT -> applyCreditStatement(row.sourceRowNumber, values)
            StructuredImportEntityType.INSTALLMENT -> listOf(applyInstallment(row.sourceRowNumber, values))
            StructuredImportEntityType.LOAN -> listOf(applyLoan(row.sourceRowNumber, values))
            StructuredImportEntityType.BUDGET -> listOf(applyBudget(row.sourceRowNumber, values))
            StructuredImportEntityType.TRANSACTION -> error("transaction rows use the financial page source")
        }
        DomainResult.Success(commits.map { StructuredAppliedCommit(row.sourceRowNumber, it) })
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
    }

    private suspend fun applyAccount(row: Long, value: Values): StableId {
        val commit = derived(row, "account:commit")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveAccount(
                    AccountDraft(
                        value.id("id"),
                        value.optionalId("ledger_account_id") ?: derived(row, "account:ledger"),
                        null,
                        value.enum("type", UserAccountType.CASH),
                        value.required("name"),
                        value.currency("currency"),
                        value.optional("institution_name"),
                        value.optional("branch_name"),
                        value.optional("account_number"),
                        value.optionalDate("opened_on"),
                        value.optional("icon_key") ?: "account",
                        value.int("color_argb", DEFAULT_COLOR),
                        value.int("sort_order", 0),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyCard(row: Long, value: Values): StableId {
        val commit = derived(row, "card:commit")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveCard(
                    CardDraft(
                        value.id("id"),
                        null,
                        value.id("account_id"),
                        value.enum("type", CardType.DEBIT),
                        value.required("name"),
                        value.optional("last_four"),
                        value.optionalId("replacement_of_id"),
                        value.optional("icon_key") ?: "card",
                        value.int("color_argb", DEFAULT_COLOR),
                        value.int("sort_order", 0),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyCategory(row: Long, value: Values): StableId {
        val commit = derived(row, "category:commit")
        val direction = value.enum("direction", CategoryDirection.EXPENSE)
        val natureDefault = if (direction == CategoryDirection.EXPENSE) {
            StatisticalNature.CONSUMPTION_EXPENSE
        } else {
            StatisticalNature.REGULAR_INCOME
        }
        val name = value.required("name")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveCategory(
                    CategoryDraft(
                        value.id("id"),
                        null,
                        direction,
                        value.optionalId("parent_id"),
                        name,
                        value.optional("normalized_name") ?: name.trim().lowercase(),
                        value.optional("icon_key") ?: "record",
                        value.int("color_argb", DEFAULT_COLOR),
                        value.int("sort_order", 0),
                        value.enum("statistical_nature", natureDefault),
                        value.optionalId("default_account_id"),
                        value.optionalId("default_card_id"),
                        value.optionalId("default_merchant_id"),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyMerchant(row: Long, value: Values): StableId {
        val commit = derived(row, "merchant:commit")
        val name = value.required("name")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveMerchant(
                    MerchantDraft(
                        value.id("id"),
                        null,
                        name,
                        value.optional("normalized_name") ?: name.trim().lowercase(),
                        value.list("aliases").toSet(),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyPlace(row: Long, value: Values): StableId {
        val commit = derived(row, "place:commit")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SavePlace(
                    PlaceDraft(
                        value.id("id"),
                        null,
                        value.required("name"),
                        value.int("latitude_e7"),
                        value.int("longitude_e7"),
                        value.optionalId("merchant_id"),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyGoal(row: Long, value: Values): StableId {
        val commit = derived(row, "goal:commit")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveGoal(
                    GoalDraft(
                        value.id("id"),
                        null,
                        value.id("account_id"),
                        value.required("name"),
                        value.long("target_amount"),
                        value.optionalDate("due_date"),
                        value.optionalLong("suggested_monthly_amount"),
                        value.enum("status", GoalStatus.ACTIVE),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyProject(row: Long, value: Values): StableId {
        val commit = derived(row, "project:commit")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveProject(
                    ProjectDraft(
                        value.id("id"),
                        null,
                        value.required("name"),
                        value.optional("description"),
                        value.date("start_date"),
                        value.optionalDate("end_date"),
                        value.long("budget_base_minor", 0L),
                        value.boolean("included_in_monthly_budget", false),
                        value.optionalId("goal_id"),
                        value.enum("status", ProjectStatus.ACTIVE),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applySettlement(row: Long, value: Values): StableId {
        val ids = value.idList("participant_ids")
        val names = value.list("participant_names")
        require(ids.size >= 2 && ids.size == names.size)
        val selfId = value.id("self_participant_id")
        val revision = settlement.snapshot(bookId).valueOrAbort().localRevision
        val commit = derived(row, "settlement:commit")
        settlement.saveActivity(
            SaveSettlementActivityRequest(
                SettlementMutationIds(
                    bookId,
                    CommandId(derived(row, "settlement:command")),
                    commit,
                    derived(row, "settlement:device"),
                    List(ids.size + 1) { derived(row, "settlement:revision:$it") },
                    ids.filterNot { it == selfId }.associateWith { derived(row, "settlement:ledger:$it") },
                    revision,
                ),
                value.id("id"),
                null,
                value.required("name"),
                value.optional("description"),
                value.currency("currency"),
                value.optionalId("project_id"),
                value.date("start_date"),
                value.optionalDate("end_date"),
                value.enum("status", SettlementActivityStatus.ACTIVE),
                ids.zip(names).map { (id, name) -> SettlementParticipantDraft(id, name, id == selfId) },
                value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyLocation(row: Long, value: Values): StableId {
        val commit = derived(row, "location:commit")
        references.mutate(
            ReferenceMutationCommand(
                referenceIds(row, commit),
                ReferenceMutation.SaveLocation(
                    OrdinaryLocationDraft(
                        value.id("id"),
                        value.int("latitude_e7"),
                        value.int("longitude_e7"),
                        value.optionalInt("accuracy_mm"),
                        value.instant("captured_at"),
                        value.enum("provider", OrdinaryLocationProvider.MANUAL),
                        value.optionalId("place_id"),
                    ),
                ),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyRecurrence(row: Long, value: Values): List<StableId> {
        val blueprintCommit = derived(row, "recurrence:blueprint:commit")
        var revision = automation.snapshot(bookId).valueOrAbort().localRevision
        val blueprintId = value.optionalId("blueprint_id") ?: derived(row, "recurrence:blueprint")
        val blueprintRevision = derived(row, "recurrence:blueprint:revision")
        automation.saveBlueprint(
            SaveBlueprintRequest(
                AutomationMutationIds(
                    bookId,
                    CommandId(derived(row, "recurrence:blueprint:command")),
                    blueprintCommit,
                    derived(row, "recurrence:blueprint:entity-revision"),
                    derived(row, "recurrence:blueprint:device"),
                    revision,
                    value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
                ),
                BlueprintDraft(
                    blueprintId,
                    blueprintRevision,
                    null,
                    value.required("name"),
                    value.optional("icon_key") ?: "recurrence",
                    value.int("color_argb", DEFAULT_COLOR),
                    EntityStatus.ACTIVE,
                    value.enum("transaction_kind", TransactionKind.EXPENSE),
                    value.optionalId("category_id"),
                    value.optionalId("primary_account_id"),
                    value.optionalId("secondary_account_id"),
                    value.optionalId("card_id"),
                    value.optionalId("merchant_id"),
                    value.optionalId("project_id"),
                    value.optionalId("goal_id"),
                    value.optionalId("settlement_activity_id"),
                    value.optional("amount_expression"),
                    value.optional("currency")?.let { CurrencyCode.parse(it).valueOrAbort() },
                    value.optional("note_template"),
                    value.optionalId("fixed_place_id"),
                ),
            ),
        ).valueOrAbort()
        revision = automation.snapshot(bookId).valueOrAbort().localRevision
        val seriesCommit = derived(row, "recurrence:series:commit")
        val frequency = value.enum("frequency", RecurrenceFrequency.MONTHLY_DAY)
        automation.saveSeries(
            SaveRecurrenceRequest(
                AutomationMutationIds(
                    bookId,
                    CommandId(derived(row, "recurrence:series:command")),
                    seriesCommit,
                    derived(row, "recurrence:series:entity-revision"),
                    derived(row, "recurrence:series:device"),
                    revision,
                    value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
                ),
                RecurrenceSeriesDraft(
                    value.id("id"),
                    derived(row, "recurrence:series:revision"),
                    null,
                    blueprintId,
                    value.enum("status", RecurrenceStatus.ACTIVE),
                    RecurrenceRule(
                        frequency,
                        value.int("interval", 1),
                        value.list("weekdays").map { DayOfWeek.valueOf(it.uppercase()) }.toSet(),
                        value.optionalInt("month_day"),
                        value.optionalInt("nth_week"),
                        value.optional("weekday")?.let { DayOfWeek.valueOf(it.uppercase()) },
                        value.enum("missing_day_policy", MissingDayPolicy.MOVE_TO_MONTH_END),
                        value.enum("weekend_adjustment", WeekendAdjustment.NONE),
                    ),
                    value.date("start_at"),
                    value.optionalDate("end_at"),
                    value.optionalInt("max_occurrences"),
                    value.optional("occurrence_time")?.let(LocalTime::parse) ?: DEFAULT_OCCURRENCE_TIME,
                    ZoneId.of(value.optional("zone_id") ?: "UTC"),
                    value.enum("generation_mode", RecurrenceGenerationMode.CANDIDATE),
                    value.optionalId("fixed_place_id"),
                    value.boolean("notify_candidate", true),
                ),
            ),
        ).valueOrAbort()
        return listOf(blueprintCommit, seriesCommit)
    }

    private suspend fun applyCreditStatement(row: Long, value: Values): List<StableId> {
        val accountId = value.id("account_id")
        val commits = mutableListOf<StableId>()
        val snapshot = credit.snapshot(bookId).valueOrAbort()
        if (snapshot.accounts.singleOrNull { it.id == accountId }?.profile == null) {
            val profileCommit = derived(row, "credit:profile:commit")
            val cycleEnd = value.date("cycle_end")
            val dueDate = value.date("due_date")
            credit.saveProfile(
                SaveCreditProfileRequest(
                    CreditMutationIds(
                        bookId,
                        CommandId(derived(row, "credit:profile:command")),
                        profileCommit,
                        derived(row, "credit:profile:device"),
                    ),
                    accountId,
                    null,
                    StatementDateRule.DayOfMonth(cycleEnd.dayOfMonth, MissingDayPolicy.MOVE_TO_MONTH_END),
                    DueDateRule.DaysAfterStatement(java.time.temporal.ChronoUnit.DAYS.between(cycleEnd, dueDate).toInt()),
                    ZoneId.of(value.optional("zone_id") ?: "UTC"),
                    value.optionalLong("standard_limit_minor"),
                    null,
                    null,
                    value.optionalId("default_payment_account_id"),
                    AutoGenerationMode.CONFIRMATION_CANDIDATE,
                    WeekendAdjustment.NONE,
                    null,
                    value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
                ),
            ).valueOrAbort()
            commits += profileCommit
        }
        val statementCommit = derived(row, "credit:statement:commit")
        val official = value.optionalLong("official_amount_minor")
        credit.saveStatement(
            SaveCreditStatementRequest(
                CreditStatementMutationIds(
                    CreditMutationIds(
                        bookId,
                        CommandId(derived(row, "credit:statement:command")),
                        statementCommit,
                        derived(row, "credit:statement:device"),
                    ),
                    value.id("id"),
                    derived(row, "credit:statement:revision"),
                ),
                accountId,
                null,
                1,
                value.date("cycle_start"),
                value.date("cycle_end"),
                value.date("due_date"),
                value.long("estimated_amount_minor", official ?: 0L),
                official,
                official?.let { value.instant("official_recorded_at", Instant.EPOCH.plusSeconds(row)) },
                value.boolean("sealed", official != null),
                value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
            ),
        ).valueOrAbort()
        commits += statementCommit
        return commits
    }

    private suspend fun applyInstallment(row: Long, value: Values): StableId {
        val termCount = value.int("term_count")
        val commit = derived(row, "installment:commit")
        installment.save(
            SaveInstallmentPlanRequest(
                InstallmentMutationIds(
                    bookId,
                    CommandId(derived(row, "installment:command")),
                    commit,
                    derived(row, "installment:device"),
                    value.id("id"),
                    derived(row, "installment:revision"),
                    derived(row, "installment:schedule-revision"),
                    List(termCount) { derived(row, "installment:item:$it") },
                ),
                value.id("purchase_transaction_id"),
                value.id("credit_account_id"),
                value.currency("currency"),
                value.long("original_principal_minor"),
                value.long("current_principal_minor", value.long("original_principal_minor")),
                termCount,
                null,
                1,
                1,
                value.date("first_statement_date"),
                InstallmentTermsDraft(
                    value.enum("fee_rate_type", InstallmentFeeRateType.NONE),
                    value.optionalLong("fixed_fee_per_term_minor"),
                    value.optionalLong("first_term_fee_minor"),
                    value.optionalDecimal("remaining_principal_rate")?.let { InterestRate.of(it).valueOrAbort() },
                    value.optionalDecimal("effective_annual_rate")?.let { InterestRate.of(it).valueOrAbort() },
                    value.enum("prepayment_policy", InstallmentPrepaymentPolicy.ALLOWED_WITHOUT_FEE),
                    value.optionalLong("prepayment_fee_minor"),
                    value.enum("refund_policy", InstallmentRefundPolicy.REBUILD_SCHEDULE),
                    value.enum("rounding_mode", RoundingMode.HALF_EVEN),
                ),
                ScheduleRevisionReason.INITIAL,
                value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyLoan(row: Long, value: Values): StableId {
        val paymentCount = value.int("payment_count")
        val trancheIds = LoanTrancheMutationIds(
            value.optionalId("tranche_id") ?: derived(row, "loan:tranche"),
            derived(row, "loan:terms-revision"),
            derived(row, "loan:schedule-revision"),
            List(paymentCount) { derived(row, "loan:item:$it") },
        )
        val commit = derived(row, "loan:commit")
        val mutationIds = LoanMutationIds(
            bookId,
            CommandId(derived(row, "loan:command")),
            commit,
            derived(row, "loan:device"),
            value.id("id"),
            listOf(trancheIds),
        )
        val startDate = value.date("start_date")
        val principal = value.long("principal")
        loan.saveContract(
            SaveLoanContractRequest(
                mutationIds,
                value.id("account_id"),
                value.required("name"),
                value.optional("lender"),
                value.currency("currency"),
                value.date("disbursement_date", startDate),
                value.enum("status", LoanStatus.ACTIVE),
                null,
                listOf(
                    LoanTrancheDraft(
                        trancheIds,
                        value.id("ledger_account_id"),
                        value.optional("tranche_name") ?: value.required("name"),
                        principal,
                        value.long("current_principal_minor", principal),
                        value.enum("status", LoanStatus.ACTIVE),
                        null,
                        1,
                        1,
                        ScheduleRevisionReason.INITIAL,
                        LoanTermsDraft(
                            value.enum("repayment_method", LoanRepaymentMethod.EQUAL_PAYMENT),
                            value.enum("rate_type", LoanRateType.FIXED),
                            value.enum("payment_frequency", PaymentFrequency.MONTHLY),
                            startDate,
                            value.date("end_date"),
                            paymentCount,
                            value.date("first_payment_date"),
                            value.enum("rounding_mode", RoundingMode.HALF_EVEN),
                            value.enum("prepayment_policy", LoanPrepaymentPolicy.ALLOWED),
                            value.enum("prepayment_strategy", PrepaymentRecalculationStrategy.SHORTEN_TERM),
                            value.optionalDecimal("penalty_rate")?.let { InterestRate.of(it).valueOrAbort() },
                            listOf(
                                LoanRatePeriod(
                                    startDate,
                                    null,
                                    InterestRate.of(value.decimal("annual_rate")).valueOrAbort(),
                                    value.optional("benchmark"),
                                    value.optionalDecimal("margin")?.let { InterestRate.of(it).valueOrAbort() },
                                ),
                            ),
                            value.long("fee_per_payment_minor", 0L),
                        ),
                    ),
                ),
                value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun applyBudget(row: Long, value: Values): StableId {
        val categories = value.idList("category_ids")
        val limits = value.longList("category_limit_minors")
        require(categories.size == limits.size)
        val commit = derived(row, "budget:commit")
        budget.saveMonth(
            SaveBudgetMonthRequest(
                BudgetMutationIds(
                    bookId,
                    CommandId(derived(row, "budget:command")),
                    commit,
                    value.id("id"),
                    derived(row, "budget:revision"),
                    List(maxOf(1, categories.size)) { derived(row, "budget:fact:$it") },
                    derived(row, "budget:device"),
                ),
                YearMonth.parse(value.required("month")),
                null,
                value.long("amount"),
                categories.zip(limits).map { BudgetCategoryLimitDraft(it.first, it.second) },
                null,
                value.instant("changed_at", Instant.EPOCH.plusSeconds(row)),
            ),
        ).valueOrAbort()
        return commit
    }

    private suspend fun referenceIds(row: Long, commit: StableId): ReferenceMutationIds = ReferenceMutationIds(
        bookId,
        references.snapshot(bookId).valueOrAbort().localRevision,
        commit,
        List(4) { derived(row, "reference:revision:$commit:$it") },
        derived(row, "reference:device:$commit"),
        Instant.EPOCH.plusSeconds(row),
    )

    private fun derived(row: Long, label: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256")
            .digest(operationId.bytes + row.toString().toByteArray(Charsets.US_ASCII) + label.toByteArray(Charsets.UTF_8))
            .copyOf(StableId.BYTE_COUNT),
    ).valueOrAbort()

    private class Values(private val values: Map<String, String>) {
        fun required(name: String): String = values[name]?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("missing $name")
        fun optional(name: String): String? = values[name]?.trim()?.takeIf(String::isNotEmpty)
        fun id(name: String): StableId = StableId.parse(required(name)).valueOrAbort()
        fun optionalId(name: String): StableId? = optional(name)?.let { StableId.parse(it).valueOrAbort() }
        fun int(name: String): Int = required(name).toInt()
        fun int(name: String, default: Int): Int = optional(name)?.toInt() ?: default
        fun optionalInt(name: String): Int? = optional(name)?.toInt()
        fun long(name: String): Long = required(name).toLong()
        fun long(name: String, default: Long): Long = optional(name)?.toLong() ?: default
        fun optionalLong(name: String): Long? = optional(name)?.toLong()
        fun decimal(name: String): BigDecimal = required(name).toBigDecimal()
        fun optionalDecimal(name: String): BigDecimal? = optional(name)?.toBigDecimal()
        fun date(name: String): LocalDate = LocalDate.parse(required(name))
        fun date(name: String, default: LocalDate): LocalDate = optional(name)?.let(LocalDate::parse) ?: default
        fun optionalDate(name: String): LocalDate? = optional(name)?.let(LocalDate::parse)
        fun instant(name: String): Instant = Instant.parse(required(name))
        fun instant(name: String, default: Instant): Instant = optional(name)?.let(Instant::parse) ?: default
        fun currency(name: String): CurrencyCode = CurrencyCode.parse(required(name)).valueOrAbort()
        fun boolean(name: String, default: Boolean): Boolean = optional(name)?.let {
            when (it.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> error("invalid boolean")
            }
        } ?: default
        fun list(name: String): List<String> = optional(name)?.split('|')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        fun idList(name: String): List<StableId> = list(name).map { StableId.parse(it).valueOrAbort() }
        fun longList(name: String): List<Long> = list(name).map(String::toLong)
        inline fun <reified T : Enum<T>> enum(name: String, default: T): T = optional(name)?.let { enumValueOf<T>(it.uppercase()) } ?: default
    }

    private companion object {
        const val DEFAULT_COLOR: Int = 0xff006c4c.toInt()
        val DEFAULT_OCCURRENCE_TIME: LocalTime = LocalTime.of(9, 0)
    }
}
