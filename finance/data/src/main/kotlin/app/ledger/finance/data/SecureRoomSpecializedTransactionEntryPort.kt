@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ReturnCount")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.network.FxQuoteNetworkPort
import app.ledger.core.network.NetworkFxQuoteRequest
import app.ledger.core.network.NetworkFxQuoteResult
import app.ledger.core.network.OkHttpFxQuoteNetworkClient
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.application.SpecializedFxQuote
import app.ledger.finance.application.SpecializedFxQuoteRequest
import app.ledger.finance.application.SpecializedTransactionEntryPort
import app.ledger.finance.application.SpecializedTransactionSnapshot
import app.ledger.finance.application.SpecializedTransactionWriteRequest
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.BalanceAdjustmentPayload
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxExchangePayload
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.OpeningBalancePayload
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.RecordBalanceAdjustmentCommand
import app.ledger.finance.domain.RecordFxExchangeCommand
import app.ledger.finance.domain.RecordOpeningBalanceCommand
import app.ledger.finance.domain.RecordTransferCommand
import app.ledger.finance.domain.ReferenceDataViolation
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.TransferPayload
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** SQLCipher-backed P14 entry/cache adapter; formal mutations terminate at FinancialMutationCoordinator. */
public class SecureRoomSpecializedTransactionEntryPort internal constructor(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val referenceDataPort: ReferenceDataManagementPort,
    private val network: FxQuoteNetworkPort,
) : SpecializedTransactionEntryPort {
    private val applicationContext = context.applicationContext
    private val writeGate = SpecializedWriteGate()
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()

    override suspend fun snapshot(bookId: app.ledger.core.common.StableId): DomainResult<SpecializedTransactionSnapshot> = when (val references = referenceDataPort.snapshot(bookId)) {
        is DomainResult.Failure -> references
        is DomainResult.Success -> DomainResult.Success(
            SpecializedTransactionSnapshot(references.value, references.value.valuationRevision),
        )
    }

    override suspend fun quote(request: SpecializedFxQuoteRequest): DomainResult<SpecializedFxQuote?> = withDatabase(request.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        if (book.id.value != request.bookId) abort(FinanceDataError.CorruptData)
        val online = if (request.refreshOnline) {
            network.quote(
                NetworkFxQuoteRequest(
                    request.sourceCurrency.value,
                    request.targetCurrency.value,
                    request.effectiveDate,
                ),
            )
        } else {
            NetworkFxQuoteResult.Unavailable
        }
        if (online is NetworkFxQuoteResult.Available) {
            val evidence = FxEvidence.create(
                FxEvidenceInput(
                    request.sourceCurrency,
                    request.targetCurrency,
                    online.quote.rate,
                    FxProvider.of(online.quote.provider).valueOrAbort(),
                    online.quote.quotedDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    online.quote.fetchedAt,
                    if (request.effectiveDate == online.quote.fetchedAt.atZone(ZoneOffset.UTC).toLocalDate()) {
                        FxRateSource.ONLINE_LATEST
                    } else {
                        FxRateSource.HISTORICAL_FALLBACK
                    },
                    false,
                ),
            ).valueOrAbort()
            if (request.effectiveDate == online.quote.fetchedAt.atZone(ZoneOffset.UTC).toLocalDate()) {
                cacheCurrentValuation(database, book.baseCurrency, evidence)
            }
            return@withDatabase DomainResult.Success(
                SpecializedFxQuote(evidence, stale(evidence.quotedAt, request.effectiveDate)),
            )
        }
        DomainResult.Success(cachedQuote(database, request, book.baseCurrency))
    }

    override suspend fun submit(request: SpecializedTransactionWriteRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = withDatabase(request.ids.bookId) { database -> execute(database, request) }

    private suspend fun execute(
        database: LedgerDatabase,
        request: SpecializedTransactionWriteRequest,
    ): DomainResult<app.ledger.finance.domain.CommandReceipt> {
        val snapshot = database.readLedger { connection -> planningSnapshot(connection, request) }
        val command = command(request, snapshot)
        val repository = RoomFinancialCommitRepository(database)
        return DefaultFinancialMutationCoordinator(
            writeGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun planningSnapshot(
        database: SupportSQLiteDatabase,
        request: SpecializedTransactionWriteRequest,
    ): PlanningSnapshot {
        val book = RoomBookRepository.mapCurrent(database)
        if (book.id.value != request.ids.bookId) abort(FinanceDataError.CorruptData)
        val references = RoomReferenceFinancialSnapshotMapper().references(database)
        val drafts = when (request) {
            is SpecializedTransactionWriteRequest.Transfer -> listOf(AmountRole.OUTGOING to request.outgoing, AmountRole.INCOMING to request.incoming)
            is SpecializedTransactionWriteRequest.BalanceAdjustment -> listOf(AmountRole.PRIMARY to request.amount)
            is SpecializedTransactionWriteRequest.FxExchange -> listOf(AmountRole.OUTGOING to request.outgoing, AmountRole.INCOMING to request.incoming)
            is SpecializedTransactionWriteRequest.OpeningBalance -> listOf(AmountRole.PRIMARY to request.amount)
        }
        if (request is SpecializedTransactionWriteRequest.Transfer) validateTransferCurrencies(references, request)
        if (request is SpecializedTransactionWriteRequest.FxExchange) validateExchangeCurrencies(references, request)
        if (request is SpecializedTransactionWriteRequest.OpeningBalance) validateOpening(database, references, request)
        val fxIds = request.ids.fxRateSnapshotIds.iterator()
        val evidence = drafts.map { (role, draft) -> frozenAmount(role, draft, references, book.baseCurrency, fxIds) }
        return PlanningSnapshot(
            book,
            null,
            null,
            emptyList(),
            emptySet(),
            emptyList(),
            null,
            emptyList(),
            AccountingPlanningContext(
                PlanningIdentitySet(
                    TransactionId(request.ids.transactionId),
                    TransactionRevisionId(request.ids.revisionId),
                    BookCommitId(request.ids.commitId),
                    request.ids.factIds,
                ),
                request.context.createdAt,
                DeviceInstanceId(request.ids.deviceInstanceId),
                references,
                evidence,
                null,
            ),
        )
    }

    private fun command(request: SpecializedTransactionWriteRequest, snapshot: PlanningSnapshot): FinancialCommand {
        val context = TransactionContextInput(
            occurredAt = EffectiveTime.fromInstant(request.context.occurredAt, request.context.zoneId),
            accrualDate = request.context.localDate,
            budgetMonth = null,
            merchantId = null,
            projectId = null,
            goalId = null,
            locationRecordId = null,
            note = request.context.note,
            amountExpression = request.context.amountExpression,
            source = TransactionSource.MANUAL,
            sourceReferenceId = null,
            statementAssignment = null,
            attachmentIds = request.context.attachmentIds.map { app.ledger.finance.domain.AttachmentId(it) },
        )
        val references = requireNotNull(snapshot.accountingContext).references
        val draft = when (request) {
            is SpecializedTransactionWriteRequest.Transfer -> RecordTransferCommand(
                request.ids.commandId,
                zeroHash(),
                NewTransactionInput(context, TransferPayload(accountAmount(request.outgoing, references), accountAmount(request.incoming, references), null)),
            )
            is SpecializedTransactionWriteRequest.BalanceAdjustment -> RecordBalanceAdjustmentCommand(
                request.ids.commandId,
                zeroHash(),
                NewTransactionInput(context, BalanceAdjustmentPayload(accountAmount(request.amount, references), request.direction, request.checkpointId)),
            )
            is SpecializedTransactionWriteRequest.FxExchange -> RecordFxExchangeCommand(
                request.ids.commandId,
                zeroHash(),
                NewTransactionInput(
                    context,
                    FxExchangePayload(
                        null,
                        accountAmount(request.outgoing, references),
                        accountAmount(request.incoming, references),
                        request.valuationPolicy,
                        request.spreadCostBaseMinor?.let { positive(it, snapshot.book.baseCurrency) },
                    ),
                ),
            )
            is SpecializedTransactionWriteRequest.OpeningBalance -> {
                val account = references.account(UserAccountId(request.amount.accountId)) ?: abort(FinanceDataError.CorruptData)
                RecordOpeningBalanceCommand(
                    request.ids.commandId,
                    zeroHash(),
                    NewTransactionInput(context, OpeningBalancePayload(accountAmount(request.amount, references), request.balanceDate, account.ledger.normalSide)),
                )
            }
        }
        val hash = CanonicalFinancialHash.command(draft)
        return when (draft) {
            is RecordTransferCommand -> draft.copy(payloadHash = hash)
            is RecordBalanceAdjustmentCommand -> draft.copy(payloadHash = hash)
            is RecordFxExchangeCommand -> draft.copy(payloadHash = hash)
            is RecordOpeningBalanceCommand -> draft.copy(payloadHash = hash)
            else -> abort(FinanceDataError.CorruptData)
        }
    }

    private fun frozenAmount(
        role: AmountRole,
        draft: SpecializedAccountAmountDraft,
        references: PlanningReferenceData,
        baseCurrency: CurrencyCode,
        fxIds: Iterator<app.ledger.core.common.StableId>,
    ): FrozenAmountEvidence {
        val account = references.account(UserAccountId(draft.accountId)) ?: abort(ReferenceDataViolation.InvalidField("specialized.account"))
        val accountMoney = positive(draft.accountMinor, account.account.currency)
        val baseMoney = positive(draft.baseMinor, baseCurrency)
        val conversion = if (account.account.currency == baseCurrency) {
            if (draft.baseMinor != draft.accountMinor || draft.accountToBaseEvidence != null) {
                abort(ReferenceDataViolation.InvalidField("specialized.baseAmount"))
            }
            null
        } else {
            val evidence = draft.accountToBaseEvidence ?: abort(ReferenceDataViolation.InvalidField("specialized.fxEvidence"))
            if (evidence.sourceCurrency != account.account.currency || evidence.targetCurrency != baseCurrency) {
                abort(ReferenceDataViolation.InvalidField("specialized.fxCurrency"))
            }
            FrozenFxConversion.create(
                FxRateSnapshotId(if (fxIds.hasNext()) fxIds.next() else abort(ReferenceDataViolation.InvalidField("specialized.fxId"))),
                accountMoney,
                baseMoney,
                evidence,
                currencyCatalog.require(account.account.currency).valueOrAbort(),
                currencyCatalog.require(baseCurrency).valueOrAbort(),
                stale(evidence.quotedAt, null),
            ).valueOrAbort()
        }
        return FrozenAmountEvidence.create(
            AmountEvidenceKey(role, 0),
            accountMoney,
            accountMoney,
            baseMoney,
            account.account.id,
            null,
            conversion,
        ).valueOrAbort()
    }

    private fun validateTransferCurrencies(references: PlanningReferenceData, request: SpecializedTransactionWriteRequest.Transfer) {
        val outgoing = references.account(UserAccountId(request.outgoing.accountId)) ?: abort(FinanceDataError.CorruptData)
        val incoming = references.account(UserAccountId(request.incoming.accountId)) ?: abort(FinanceDataError.CorruptData)
        if (outgoing.account.currency == incoming.account.currency && request.outgoing.accountMinor != request.incoming.accountMinor) {
            abort(ReferenceDataViolation.InvalidField("transfer.sameCurrencyAmount"))
        }
    }

    private fun validateExchangeCurrencies(references: PlanningReferenceData, request: SpecializedTransactionWriteRequest.FxExchange) {
        val outgoing = references.account(UserAccountId(request.outgoing.accountId)) ?: abort(FinanceDataError.CorruptData)
        val incoming = references.account(UserAccountId(request.incoming.accountId)) ?: abort(FinanceDataError.CorruptData)
        if (outgoing.account.currency == incoming.account.currency) {
            abort(ReferenceDataViolation.InvalidField("fxExchange.sameCurrency"))
        }
    }

    private fun validateOpening(
        database: SupportSQLiteDatabase,
        references: PlanningReferenceData,
        request: SpecializedTransactionWriteRequest.OpeningBalance,
    ) {
        val account = references.account(UserAccountId(request.amount.accountId)) ?: abort(FinanceDataError.CorruptData)
        if (account.account.hasFinancialPostings) abort(ReferenceDataViolation.InvalidField("openingBalance.account"))
        val existing = database.queryOne(
            "SELECT COUNT(*) FROM opening_balance_revision_detail obd JOIN user_account ua ON ua.id=obd.account_id WHERE ua.uid=?",
            arrayOf(request.amount.accountId.bytes),
        ) { it.getLong(0) } ?: 0L
        if (existing != 0L) abort(ReferenceDataViolation.InvalidField("openingBalance.duplicate"))
    }

    private fun cachedQuote(
        database: LedgerDatabase,
        request: SpecializedFxQuoteRequest,
        baseCurrency: CurrencyCode,
    ): SpecializedFxQuote? = database.readLedger { connection ->
        val source = valuationRate(connection, request.sourceCurrency, baseCurrency) ?: return@readLedger null
        val target = valuationRate(connection, request.targetCurrency, baseCurrency) ?: return@readLedger null
        val rate = source.rate.divide(target.rate, MATH_CONTEXT).stripTrailingZeros()
        val quotedAt = listOfNotNull(source.quotedAt, target.quotedAt).minOrNull()
        val evidence = FxEvidence.create(
            FxEvidenceInput(
                request.sourceCurrency,
                request.targetCurrency,
                rate,
                FxProvider.of("valuation-cache").valueOrAbort(),
                quotedAt,
                null,
                FxRateSource.CACHE,
                false,
            ),
        ).valueOrAbort()
        SpecializedFxQuote(evidence, stale(quotedAt, request.effectiveDate))
    }

    private fun valuationRate(
        database: SupportSQLiteDatabase,
        currency: CurrencyCode,
        baseCurrency: CurrencyCode,
    ): CachedRate? {
        if (currency == baseCurrency) return CachedRate(BigDecimal.ONE, null)
        return database.queryOne(
            "SELECT avc.rate_decimal,avc.rate_quoted_at FROM account_valuation_current avc " +
                "JOIN user_account ua ON ua.id=avc.account_id WHERE ua.currency_code=? ORDER BY avc.rate_quoted_at DESC LIMIT 1",
            arrayOf(currency.value),
        ) { cursor -> CachedRate(cursor.getString(0).toBigDecimal(), cursor.nullableLong("rate_quoted_at")?.toStoredInstant()) }
    }

    private fun cacheCurrentValuation(database: LedgerDatabase, baseCurrency: CurrencyCode, evidence: FxEvidence) {
        val normalized = if (evidence.targetCurrency == baseCurrency) {
            evidence.sourceCurrency to evidence.rate
        } else if (evidence.sourceCurrency == baseCurrency) {
            evidence.targetCurrency to BigDecimal.ONE.divide(evidence.rate, MATH_CONTEXT)
        } else {
            return
        }
        database.inLedgerTransaction { connection ->
            val state = connection.queryOne(
                "SELECT local_revision,valuation_revision FROM book WHERE id=1",
            ) { it.getLong(0) to it.getLong(1) } ?: abort(FinanceDataError.CorruptData)
            val valuationRevision = CheckedArithmetic.add(state.second, 1L).valueOrAbort()
            connection.execSQL(
                "UPDATE account_valuation_current SET as_of_valuation_revision=?",
                arrayOf<Any>(valuationRevision),
            )
            val metadata = currencyCatalog.require(normalized.first).valueOrAbort()
            val baseMetadata = currencyCatalog.require(baseCurrency).valueOrAbort()
            val accounts = connection.queryList(
                "SELECT ua.id,COALESCE(abc.normal_balance_minor,0) balance_minor FROM user_account ua " +
                    "LEFT JOIN account_balance_current abc ON abc.account_id=ua.id WHERE ua.currency_code=? AND ua.status=0",
                arrayOf(normalized.first.value),
            ) { it.getLong(0) to it.getLong(1) }
            accounts.forEach { (accountId, balanceMinor) ->
                val baseValue = Money.fromMajor(
                    Money(balanceMinor, normalized.first).toMajor(metadata).valueOrAbort().multiply(normalized.second, MATH_CONTEXT),
                    baseMetadata,
                    RoundingMode.HALF_EVEN,
                ).valueOrAbort().minor
                connection.execSQL(
                    "INSERT OR REPLACE INTO account_valuation_current(account_id,balance_minor,current_base_value_minor,rate_decimal," +
                        "rate_quoted_at,as_of_local_revision,as_of_valuation_revision) VALUES(?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        accountId,
                        balanceMinor,
                        baseValue,
                        normalized.second.stripTrailingZeros().toPlainString(),
                        evidence.quotedAt?.toStorageEpochMillis(),
                        state.first,
                        valuationRevision,
                    ),
                )
            }
            connection.execSQL("DELETE FROM widget_book_snapshot")
            insertCurrentWidgetBook(connection, state.first, valuationRevision)
            val changed = connection.compileStatement(
                "UPDATE book SET valuation_revision=? WHERE id=1 AND local_revision=? AND valuation_revision=?",
            ).apply {
                bindLong(1, valuationRevision)
                bindLong(2, state.first)
                bindLong(3, state.second)
            }.executeUpdateDelete()
            if (changed != 1) abort(FinanceDataError.ProjectionMismatch)
        }
    }

    private fun insertCurrentWidgetBook(database: SupportSQLiteDatabase, localRevision: Long, valuationRevision: Long) {
        database.execSQL(
            """
            INSERT INTO widget_book_snapshot(id,core_net_financial_assets_base_minor,adjusted_net_financial_position_base_minor,
              base_currency,as_of_local_revision,as_of_valuation_revision)
            WITH core AS (
              SELECT COALESCE(SUM(CASE WHEN ua.type IN (0,1) THEN
                CASE WHEN abc.currency_code=b.base_currency THEN abc.normal_balance_minor ELSE COALESCE(avc.current_base_value_minor,0) END
                ELSE -CASE WHEN abc.currency_code=b.base_currency THEN ABS(abc.normal_balance_minor) ELSE ABS(COALESCE(avc.current_base_value_minor,0)) END END),0) value
              FROM book b JOIN user_account ua LEFT JOIN account_balance_current abc ON abc.account_id=ua.id
                LEFT JOIN account_valuation_current avc ON avc.account_id=ua.id
            ), settlement AS (
              SELECT COALESCE(SUM(spp.net_position_minor),0) value FROM settlement_position_projection spp
                JOIN participant p ON p.id=spp.participant_id WHERE p.is_self=1
            )
            SELECT 1,core.value,core.value+settlement.value,b.base_currency,?,? FROM book b,core,settlement WHERE b.id=1
            """.trimIndent(),
            arrayOf<Any>(localRevision, valuationRevision),
        )
    }

    private suspend fun <T> withDatabase(
        bookId: app.ledger.core.common.StableId,
        block: suspend (LedgerDatabase) -> DomainResult<T>,
    ): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private fun accountAmount(draft: SpecializedAccountAmountDraft, references: PlanningReferenceData): AccountAmount {
        val account = references.account(UserAccountId(draft.accountId))?.account ?: abort(FinanceDataError.CorruptData)
        return AccountAmount.create(account, Money(draft.accountMinor, account.currency)).valueOrAbort()
    }

    private fun positive(minor: Long, currency: CurrencyCode): PositiveMoney = PositiveMoney.from(Money(minor, currency)).valueOrAbort()

    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(32)).valueOrAbort()

    private fun stale(quotedAt: Instant?, effectiveDate: java.time.LocalDate?): Boolean = quotedAt != null && effectiveDate != null && quotedAt.atZone(ZoneOffset.UTC).toLocalDate().isBefore(effectiveDate.minusDays(STALE_DAYS))

    private data class CachedRate(val rate: BigDecimal, val quotedAt: Instant?)

    public companion object {
        public fun production(
            context: Context,
            keyProvider: DeviceLedgerKeyProvider,
            referenceDataPort: ReferenceDataManagementPort,
            instantSource: () -> Instant,
        ): SpecializedTransactionEntryPort = SecureRoomSpecializedTransactionEntryPort(
            context,
            keyProvider,
            referenceDataPort,
            OkHttpFxQuoteNetworkClient.production { instantSource() },
        )

        private const val STALE_DAYS = 3L
        private val MATH_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
    }
}

private class SpecializedWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}
