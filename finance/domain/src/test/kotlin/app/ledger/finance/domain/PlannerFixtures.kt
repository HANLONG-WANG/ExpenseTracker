package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.CurrencyMetadata
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal object PlannerFixtures {
    val jpy: CurrencyCode = currency("JPY")
    val usd: CurrencyCode = currency("USD")

    val expenseCategoryId = CategoryId(stableId(701))
    val nonConsumptionCategoryId = CategoryId(stableId(703))
    val incomeCategoryId = CategoryId(stableId(702))
    val bankJpyId = UserAccountId(stableId(710))
    val bankUsdId = UserAccountId(stableId(711))
    val bankJpyTwoId = UserAccountId(stableId(712))
    val creditJpyId = UserAccountId(stableId(713))
    val projectId = ProjectId(stableId(720))
    val goalId = GoalId(stableId(721))
    val loanContractId = LoanContractId(stableId(730))
    val loanTrancheId = LoanTrancheId(stableId(731))
    val activityId = SettlementActivityId(stableId(740))
    val selfId = ParticipantId(stableId(741))
    val friendId = ParticipantId(stableId(742))

    fun references(): PlanningReferenceData {
        val accounts = listOf(
            account(bankJpyId, UserAccountType.BANK, jpy, LedgerAccountClass.ASSET, 800),
            account(bankUsdId, UserAccountType.BANK, usd, LedgerAccountClass.ASSET, 801),
            account(bankJpyTwoId, UserAccountType.BANK, jpy, LedgerAccountClass.ASSET, 802),
            account(creditJpyId, UserAccountType.CREDIT, jpy, LedgerAccountClass.LIABILITY, 803),
        )
        val systemLedgers = SystemLedgerCode.entries.mapIndexed { index, code ->
            val accountClass = when (code) {
                SystemLedgerCode.SYSTEM_INCOME_REGULAR,
                SystemLedgerCode.SYSTEM_INCOME_NON_RECURRING,
                SystemLedgerCode.SYSTEM_FX_GAIN,
                -> LedgerAccountClass.INCOME
                SystemLedgerCode.SYSTEM_EXPENSE_CONSUMPTION,
                SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION,
                SystemLedgerCode.SYSTEM_FX_COST,
                -> LedgerAccountClass.EXPENSE
                SystemLedgerCode.SYSTEM_OPENING_EQUITY,
                SystemLedgerCode.SYSTEM_BALANCE_ADJUSTMENT,
                -> LedgerAccountClass.EQUITY
                SystemLedgerCode.SYSTEM_FX_CLEARING,
                SystemLedgerCode.SYSTEM_FX_ROUNDING,
                -> LedgerAccountClass.CLEARING
            }
            PlanningSystemLedger(
                code,
                ledger(
                    id = 900L + index,
                    currency = jpy,
                    accountClass = accountClass,
                    normalSide = when (accountClass) {
                        LedgerAccountClass.ASSET,
                        LedgerAccountClass.EXPENSE,
                        LedgerAccountClass.CLEARING,
                        -> DebitCredit.DEBIT
                        else -> DebitCredit.CREDIT
                    },
                ),
            )
        }
        return PlanningReferenceData(
            accounts = accounts,
            cards = emptyList(),
            categories = listOf(
                PlanningCategory(
                    expenseCategoryId,
                    expenseCategoryId,
                    CategoryDirection.EXPENSE,
                    StatisticalNature.CONSUMPTION_EXPENSE,
                    CategoryStatus.ACTIVE,
                ),
                PlanningCategory(
                    incomeCategoryId,
                    incomeCategoryId,
                    CategoryDirection.INCOME,
                    StatisticalNature.REGULAR_INCOME,
                    CategoryStatus.ACTIVE,
                ),
                PlanningCategory(
                    nonConsumptionCategoryId,
                    nonConsumptionCategoryId,
                    CategoryDirection.EXPENSE,
                    StatisticalNature.NON_CONSUMPTION_EXPENSE,
                    CategoryStatus.ACTIVE,
                ),
            ),
            projects = listOf(PlanningProject(projectId, includedInMonthlyBudget = true, ProjectStatus.ACTIVE)),
            goals = listOf(PlanningGoal(goalId, bankJpyId, jpy, GoalStatus.ACTIVE)),
            systemLedgers = systemLedgers,
            loanLedgers = listOf(
                PlanningLoanLedger(
                    loanContractId,
                    loanTrancheId,
                    ledger(950, jpy, LedgerAccountClass.LIABILITY, DebitCredit.CREDIT),
                ),
            ),
            settlementLedgers = listOf(
                PlanningSettlementLedger(
                    activityId,
                    friendId,
                    ledger(960, jpy, LedgerAccountClass.SETTLEMENT, DebitCredit.DEBIT),
                ),
            ),
        )
    }

    fun accountAmount(
        id: UserAccountId,
        minor: Long,
        references: PlanningReferenceData = references(),
    ): AccountAmount = AccountAmount.create(
        references.account(id)!!.account,
        Money(minor, references.account(id)!!.account.currency),
    ).success()

    fun sameCurrencyEvidence(
        role: AmountRole,
        minor: Long,
        accountId: UserAccountId?,
        componentIndex: Int = 0,
        currency: CurrencyCode = jpy,
    ): FrozenAmountEvidence {
        val amount = positive(minor, currency)
        return FrozenAmountEvidence.create(
            AmountEvidenceKey(role, componentIndex),
            amount,
            amount,
            amount,
            accountId,
            null,
            null,
        ).success()
    }

    @Suppress("LongParameterList")
    fun usdToJpyEvidence(
        role: AmountRole,
        usdMinor: Long,
        jpyMinor: Long,
        accountId: UserAccountId,
        rate: String,
        id: Long,
    ): FrozenAmountEvidence {
        val source = positive(usdMinor, usd)
        val target = positive(jpyMinor, jpy)
        val evidence = FxEvidence.create(
            FxEvidenceInput(
                sourceCurrency = usd,
                targetCurrency = jpy,
                rate = BigDecimal(rate),
                provider = FxProvider.of("P06_TEST").success(),
                quotedAt = Instant.parse("2026-07-31T12:00:00Z"),
                fetchedAt = Instant.parse("2026-07-31T12:00:01Z"),
                source = FxRateSource.OFFICIAL_SETTLEMENT,
                manuallyOverridden = false,
            ),
        ).success()
        val conversion = FrozenFxConversion.create(
            FxRateSnapshotId(stableId(id)),
            source,
            target,
            evidence,
            CurrencyMetadata(usd, 840, 2),
            CurrencyMetadata(jpy, 392, 0),
            staleAtUse = false,
        ).success()
        return FrozenAmountEvidence.create(
            AmountEvidenceKey(role, 0),
            source,
            source,
            target,
            accountId,
            null,
            conversion,
        ).success()
    }

    fun inputContext(
        occurredAt: EffectiveTime = EffectiveTime.fromInstant(
            Instant.parse("2026-07-31T15:30:00Z"),
            ZoneId.of("America/Los_Angeles"),
        ),
        project: ProjectId? = null,
        goal: GoalId? = null,
        source: TransactionSource = TransactionSource.MANUAL,
    ): TransactionContextInput = TransactionContextInput(
        occurredAt = occurredAt,
        accrualDate = occurredAt.localDate,
        budgetMonth = YearMonth.from(occurredAt.localDate),
        merchantId = null,
        projectId = project,
        goalId = goal,
        locationRecordId = null,
        note = "private-note-is-hashed-not-logged",
        amountExpression = "1000",
        source = source,
        sourceReferenceId = null,
        statementAssignment = null,
        attachmentIds = emptyList(),
    )

    fun identities(seed: Long): PlanningIdentitySet = PlanningIdentitySet(
        transactionId = TransactionId(stableId(seed)),
        revisionId = TransactionRevisionId(stableId(seed + 1L)),
        commitId = BookCommitId(stableId(seed + 2L)),
        factIds = (seed + 10L..seed + 250L).map(::stableId),
    )

    @Suppress("LongParameterList")
    fun snapshot(
        amountEvidence: List<FrozenAmountEvidence>,
        seed: Long = 10_000L,
        currentTransaction: BusinessTransaction? = null,
        currentRevision: TransactionRevision? = null,
        currentFacts: CurrentFinancialFacts? = null,
        sourceBook: Book = book(),
        dependencies: List<TransactionDependency> = emptyList(),
        reversedApplyEntryIds: Set<JournalEntryId> = emptySet(),
        referenceData: PlanningReferenceData = references(),
    ): PlanningSnapshot = PlanningSnapshot(
        book = sourceBook,
        currentTransaction = currentTransaction,
        currentRevision = currentRevision,
        dependencies = dependencies,
        reversedApplyEntryIds = reversedApplyEntryIds,
        refundStatuses = emptyList(),
        budgetRevision = null,
        participants = participants(),
        accountingContext = AccountingPlanningContext(
            identities = identities(seed).let { generated ->
                if (currentTransaction == null) generated else generated.copy(transactionId = currentTransaction.id)
            },
            createdAt = Instant.ofEpochSecond(seed),
            deviceInstanceId = DeviceInstanceId(stableId(seed + 3L)),
            references = referenceData,
            amountEvidence = amountEvidence,
            currentFacts = currentFacts,
        ),
    )

    fun participants(): List<Participant> = listOf(
        Participant(selfId, "self", isSelf = true, EntityStatus.ACTIVE, BookCommitId(stableId(760))),
        Participant(friendId, "friend", isSelf = false, EntityStatus.ACTIVE, BookCommitId(stableId(761))),
    )

    fun expenseCommand(
        minor: Long,
        accountId: UserAccountId = bankJpyId,
        context: TransactionContextInput = inputContext(),
        commandSeed: Long = 20_000L,
        references: PlanningReferenceData = references(),
    ): RecordExpenseCommand {
        val classification = CategoryAssignment(
            expenseCategoryId,
            CategoryDirection.EXPENSE,
            StatisticalNature.CONSUMPTION_EXPENSE,
        )
        val command = RecordExpenseCommand(
            commandId = CommandId(stableId(commandSeed)),
            payloadHash = hash(0),
            input = NewTransactionInput(
                context,
                ExpensePayload(
                    classification,
                    ExpensePayer.LocalAccount(accountAmount(accountId, minor, references), null),
                    positive(minor, references.account(accountId)!!.account.currency),
                    null,
                    emptyList(),
                    null,
                ),
            ),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    fun nextBook(plan: FinancialMutationPlan, previous: Book): Book = previous.copy(
        headCommitId = plan.commit.id,
        localRevision = plan.targetLocalRevision,
    )

    fun currentFacts(plan: FinancialMutationPlan): CurrentFinancialFacts = CurrentFinancialFacts(
        journalBundles = plan.journalBundles.filter { it.entry.role == JournalEntryRole.APPLY },
        economicEffects = plan.economicEffects.filter { it.polarity == EffectPolarity.APPLY },
        budgetEffects = plan.budgetEffects.filter { it.polarity == EffectPolarity.APPLY },
        projectEffects = plan.projectEffects.filter { it.polarity == EffectPolarity.APPLY },
        goalEffects = plan.goalEffects.filter { it.polarity == EffectPolarity.APPLY },
        statementEffects = plan.statementEffects.filter { it.polarity == EffectPolarity.APPLY },
        loanEffects = plan.loanEffects.filter { it.polarity == EffectPolarity.APPLY },
        settlementEffects = plan.settlementEffects.filter { it.reversalOfId == null },
    )

    private fun account(
        id: UserAccountId,
        type: UserAccountType,
        currency: CurrencyCode,
        accountClass: LedgerAccountClass,
        ledgerId: Long,
    ): PlanningAccount {
        val ledger = ledger(
            ledgerId,
            currency,
            accountClass,
            if (accountClass == LedgerAccountClass.LIABILITY) DebitCredit.CREDIT else DebitCredit.DEBIT,
        )
        return PlanningAccount(
            AccountSnapshot(id, ledger.id, type, currency, EntityStatus.ACTIVE, rowVersion(), true),
            ledger,
        )
    }

    private fun ledger(
        id: Long,
        currency: CurrencyCode,
        accountClass: LedgerAccountClass,
        normalSide: DebitCredit,
    ): LedgerAccountSnapshot = LedgerAccountSnapshot(
        LedgerAccountId(stableId(id)),
        accountClass,
        normalSide,
        currency,
        EntityStatus.ACTIVE,
    )
}
