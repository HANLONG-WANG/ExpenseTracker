@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerDatabaseOperationAccess
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.CallerOwnedLedgerWriteGate
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.OpeningBalanceWritePort
import app.ledger.finance.application.OpeningBalanceWriteRequest
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountSnapshot
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.LedgerAccountId
import app.ledger.finance.domain.LedgerAccountSnapshot
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.OpeningBalancePayload
import app.ledger.finance.domain.PlanningAccount
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PlanningSystemLedger
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.RecordOpeningBalanceCommand
import app.ledger.finance.domain.ReferenceDataViolation
import app.ledger.finance.domain.RowVersion
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.UserAccountType
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.YearMonth

/** Opens the encrypted book, plans deterministically, and commits only through FinancialMutationCoordinator. */
public class SecureRoomOpeningBalanceWritePort(
    private val databaseAccess: LedgerDatabaseOperationAccess,
) : OpeningBalanceWritePort {
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()

    override suspend fun record(request: OpeningBalanceWriteRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = try {
        databaseAccess.withCurrentDatabase(request.ids.bookId, LedgerAccessMode.WRITE) { database ->
            execute(database, request)
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private suspend fun execute(database: LedgerDatabase, request: OpeningBalanceWriteRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> {
        val snapshot = database.readLedger { connection -> planningSnapshot(connection, request) }
        val account = requireNotNull(snapshot.accountingContext).references.accounts.single { it.account.id.value == request.accountId }
        val accountMoney = Money(request.accountMinor, account.account.currency)
        val accountAmount = AccountAmount.create(account.account, accountMoney).valueOrAbort()
        val input = NewTransactionInput(
            TransactionContextInput(
                occurredAt = EffectiveTime.fromInstant(
                    request.balanceDate.atStartOfDay(snapshot.book.defaultZoneId).toInstant(),
                    snapshot.book.defaultZoneId,
                ),
                accrualDate = request.balanceDate,
                budgetMonth = YearMonth.from(request.balanceDate),
                merchantId = null,
                projectId = null,
                goalId = null,
                locationRecordId = null,
                note = null,
                amountExpression = null,
                source = TransactionSource.MANUAL,
                sourceReferenceId = null,
                statementAssignment = null,
                attachmentIds = emptyList(),
            ),
            OpeningBalancePayload(accountAmount, request.balanceDate, account.ledger.normalSide),
        )
        val draft = RecordOpeningBalanceCommand(request.ids.commandId, app.ledger.finance.domain.Hash256.fromBytes(ByteArray(32)).valueOrAbort(), input)
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val repository = RoomFinancialCommitRepository(database)
        return DefaultFinancialMutationCoordinator(
            writeGate = CallerOwnedLedgerWriteGate,
            receiptRepository = repository,
            snapshotRepository = object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: app.ledger.finance.domain.FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            planner = FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            commitRepository = repository,
        ).execute(command)
    }

    private fun planningSnapshot(db: SupportSQLiteDatabase, request: OpeningBalanceWriteRequest): PlanningSnapshot {
        val book = RoomBookRepository.mapCurrent(db)
        if (book.id.value != request.ids.bookId) abort(FinanceDataError.CorruptData)
        val accounts = db.queryList(
            """
            SELECT ua.uid account_uid, la.uid ledger_uid, ua.type, ua.currency_code, ua.status, ua.row_version,
              EXISTS(SELECT 1 FROM posting p WHERE p.ledger_account_id=la.id) has_postings,
              la.account_class, la.normal_side, la.status ledger_status
            FROM user_account ua JOIN ledger_account la ON la.id=ua.ledger_account_id
            """.trimIndent(),
        ) { cursor ->
            val currency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))).valueOrAbort()
            val ledger = LedgerAccountSnapshot(
                LedgerAccountId(cursor.stableId("ledger_uid")),
                LedgerAccountClass.entries[cursor.getInt(cursor.getColumnIndexOrThrow("account_class"))],
                DebitCredit.entries[cursor.getInt(cursor.getColumnIndexOrThrow("normal_side"))],
                currency,
                EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("ledger_status"))],
            )
            PlanningAccount(
                AccountSnapshot(
                    UserAccountId(cursor.stableId("account_uid")),
                    ledger.id,
                    UserAccountType.entries[cursor.getInt(cursor.getColumnIndexOrThrow("type"))],
                    currency,
                    EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
                    RowVersion.of(cursor.getLong(cursor.getColumnIndexOrThrow("row_version"))).valueOrAbort(),
                    cursor.getInt(cursor.getColumnIndexOrThrow("has_postings")) == 1,
                ),
                ledger,
            )
        }
        val target = accounts.singleOrNull { it.account.id.value == request.accountId } ?: abort(FinanceDataError.CorruptData)
        if (target.account.status != EntityStatus.ACTIVE || target.account.hasFinancialPostings) {
            abort(ReferenceDataViolation.InvalidField("openingBalance.account"))
        }
        val systems = db.queryList(
            "SELECT uid,account_class,normal_side,currency_code,status,system_code FROM ledger_account WHERE owner_type=2",
        ) { cursor ->
            PlanningSystemLedger(
                SystemLedgerCode.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("system_code"))),
                LedgerAccountSnapshot(
                    LedgerAccountId(cursor.stableId("uid")),
                    LedgerAccountClass.entries[cursor.getInt(cursor.getColumnIndexOrThrow("account_class"))],
                    DebitCredit.entries[cursor.getInt(cursor.getColumnIndexOrThrow("normal_side"))],
                    CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))).valueOrAbort(),
                    EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
                ),
            )
        }
        val references = PlanningReferenceData(accounts, emptyList(), emptyList(), emptyList(), emptyList(), systems, emptyList(), emptyList())
        val accountPositive = PositiveMoney.from(Money(request.accountMinor, target.account.currency)).valueOrAbort()
        if (target.account.currency != book.baseCurrency && request.baseMinor == null) {
            abort(ReferenceDataViolation.InvalidField("openingBalance.baseAmount"))
        }
        if (target.account.currency == book.baseCurrency && request.baseMinor != null && request.baseMinor != request.accountMinor) {
            abort(ReferenceDataViolation.InvalidField("openingBalance.baseAmount"))
        }
        val basePositive = PositiveMoney.from(Money(request.baseMinor ?: request.accountMinor, book.baseCurrency)).valueOrAbort()
        val conversion = if (target.account.currency == book.baseCurrency) null else conversion(request, accountPositive, basePositive)
        val evidence = FrozenAmountEvidence.create(
            AmountEvidenceKey(AmountRole.PRIMARY, 0),
            accountPositive,
            accountPositive,
            basePositive,
            target.account.id,
            null,
            conversion,
        ).valueOrAbort()
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
                    TransactionRevisionId(request.ids.transactionRevisionId),
                    BookCommitId(request.ids.commitId),
                    request.ids.factIds,
                ),
                request.createdAt,
                DeviceInstanceId(request.ids.deviceInstanceId),
                references,
                listOf(evidence),
                null,
            ),
        )
    }

    private fun conversion(
        request: OpeningBalanceWriteRequest,
        account: PositiveMoney,
        base: PositiveMoney,
    ): FrozenFxConversion {
        val sourceMetadata = currencyCatalog.require(account.currency).valueOrAbort()
        val targetMetadata = currencyCatalog.require(base.currency).valueOrAbort()
        val sourceMajor = account.money.toMajor(sourceMetadata).valueOrAbort()
        val targetMajor = base.money.toMajor(targetMetadata).valueOrAbort()
        val rate = targetMajor.divide(sourceMajor, MathContext(34, RoundingMode.HALF_EVEN))
        val evidence = FxEvidence.create(
            FxEvidenceInput(
                account.currency,
                base.currency,
                rate,
                FxProvider.of("manual").valueOrAbort(),
                request.createdAt,
                request.createdAt,
                FxRateSource.MANUAL,
                true,
            ),
        ).valueOrAbort()
        return FrozenFxConversion.create(
            FxRateSnapshotId(request.ids.fxRateSnapshotId),
            account,
            base,
            evidence,
            sourceMetadata,
            targetMetadata,
            false,
        ).valueOrAbort()
    }
}
