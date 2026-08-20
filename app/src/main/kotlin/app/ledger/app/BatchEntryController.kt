@file:Suppress(
    "ComplexCondition",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package app.ledger.app

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.feature.record.BatchRecordPolicy
import app.ledger.feature.record.BatchRecordPresentation
import app.ledger.feature.record.BatchRecordState
import app.ledger.feature.record.BatchRowDraft
import app.ledger.feature.record.BatchRowKind
import app.ledger.feature.record.BatchSort
import app.ledger.finance.application.BatchEntryApplicationPort
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BatchEntryRowWriteRequest
import app.ledger.finance.application.BatchEntrySubmitRequest
import app.ledger.finance.application.BatchUndoRequest
import app.ledger.finance.application.BatchUndoRowIds
import app.ledger.finance.application.BatchValidationIssue
import app.ledger.finance.application.BatchValidationReport
import app.ledger.finance.application.BatchValidationSeverity
import app.ledger.finance.application.InstallmentApplicationPort
import app.ledger.finance.application.InstallmentSnapshot
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinarySettlementShareDraft
import app.ledger.finance.application.OrdinaryTransactionEntryPort
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.RefundAllocationDraft
import app.ledger.finance.application.RefundAmountDraft
import app.ledger.finance.application.RefundApplicationPort
import app.ledger.finance.application.RefundSnapshot
import app.ledger.finance.application.RefundWriteIds
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.SettlementShare
import app.ledger.finance.domain.TransactionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Owns only an in-memory P24 draft. No row content is written to SavedState or a route. */
internal class BatchEntryController(
    private val application: BatchEntryApplicationPort,
    private val ordinary: OrdinaryTransactionEntryPort,
    private val refunds: RefundApplicationPort,
    private val installments: InstallmentApplicationPort,
    private val runtime: AppRuntimeSources,
) {
    private val mutableState = MutableStateFlow<BatchRecordState?>(null)
    val state: StateFlow<BatchRecordState?> = mutableState.asStateFlow()
    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()
    private var refundSnapshot: RefundSnapshot? = null
    private var installmentSnapshot: InstallmentSnapshot? = null
    private var preparedRequest: BatchEntrySubmitRequest? = null
    private val currencies = JvmLegalTenderCurrencyCatalog.create()
    private val batchFxProvider = requireNotNull(FxProvider.of("batch-entry").getOrNull())

    suspend fun open(bookId: StableId, zoneId: ZoneId): Boolean {
        val ordinarySnapshot = (ordinary.snapshot(bookId) as? DomainResult.Success)?.value ?: return false
        refundSnapshot = (refunds.snapshot(bookId) as? DomainResult.Success)?.value
        installmentSnapshot = (installments.snapshot(bookId, runtime.clock.now().atZone(zoneId).toLocalDate()) as? DomainResult.Success)?.value
        val first = BatchRecordPolicy.newRow(nextId(), ordinarySnapshot, runtime.clock.now(), zoneId)
        preparedRequest = null
        mutableState.value = BatchRecordState(ordinarySnapshot, listOf(first))
        return true
    }

    fun add() = edit { current ->
        current.copy(rows = current.rows + BatchRecordPolicy.newRow(nextId(), current.snapshot, runtime.clock.now(), current.rows.firstOrNull()?.zoneId ?: ZoneId.of("UTC")))
    }

    fun copy(rowId: StableId) = edit { current ->
        val source = current.rows.singleOrNull { it.rowId == rowId } ?: return@edit current
        current.copy(rows = BatchRecordPolicy.insertAfter(current.rows, rowId, BatchRecordPolicy.copyRow(source, nextId())))
    }

    fun delete(rowId: StableId) = edit { current ->
        val remaining = current.rows.filterNot { it.rowId == rowId }
        current.copy(rows = remaining, editingRowId = current.editingRowId.takeUnless { it == rowId })
    }

    fun move(rowId: StableId, targetIndex: Int) = edit { current ->
        if (current.rows.isEmpty()) current else current.copy(rows = BatchRecordPolicy.move(current.rows, rowId, targetIndex.coerceIn(current.rows.indices)))
    }

    fun sort(order: BatchSort) = edit { it.copy(rows = BatchRecordPolicy.sort(it.rows, order)) }

    fun paste(text: String) = edit { current ->
        if (text.isBlank()) return@edit current
        val result = BatchRecordPolicy.paste(text, current.snapshot, { nextId() }, runtime.clock.now(), current.rows.firstOrNull()?.zoneId ?: ZoneId.of("UTC"))
        val pasteIssues = result.rejectedLineNumbers.map {
            BatchValidationIssue(null, BatchEntryField.BATCH, "PASTE_ROW_REJECTED", BatchValidationSeverity.ERROR)
        }
        current.copy(rows = current.rows + result.rows, validation = BatchValidationReport(pasteIssues))
    }

    fun selectRow(rowId: StableId) = editWithoutInvalidating { current ->
        current.copy(editingRowId = rowId.takeIf { id -> current.rows.any { it.rowId == id } })
    }

    fun updateRow(row: BatchRowDraft) = edit { current ->
        current.copy(rows = current.rows.map { if (it.rowId == row.rowId) row else it })
    }

    fun attach(rowId: StableId, attachmentId: StableId) = edit { current ->
        current.copy(
            rows = current.rows.map { row ->
                if (row.rowId == rowId && attachmentId !in row.attachmentIds) row.copy(attachmentIds = row.attachmentIds + attachmentId) else row
            },
        )
    }

    fun cycle(rowId: StableId, field: BatchEntryField) = edit { current ->
        current.copy(rows = current.rows.map { row -> if (row.rowId == rowId) cycleRow(row, current, field) else row })
    }

    suspend fun validate(): Boolean {
        val current = mutableState.value ?: return false
        mutableState.value = current.copy(presentation = BatchRecordPresentation.VALIDATING)
        val local = BatchRecordPolicy.validateRows(current.rows, current.snapshot)
        val request = buildRequest(current, warningsConfirmed = current.warningsConfirmed)
        val authoritative = if (request == null) {
            BatchValidationReport(listOf(BatchValidationIssue(null, BatchEntryField.BATCH, "BATCH_REQUEST_INVALID", BatchValidationSeverity.ERROR)))
        } else {
            when (val result = application.validate(request)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> BatchValidationReport(listOf(BatchValidationIssue(null, BatchEntryField.BATCH, "BATCH_DOMAIN_INVALID", BatchValidationSeverity.ERROR)))
            }
        }
        val merged = BatchRecordPolicy.mergeValidation(local, authoritative)
        preparedRequest = request
        mutableState.value = current.copy(
            presentation = if (merged.errors.isNotEmpty()) BatchRecordPresentation.ERRORS else BatchRecordPresentation.READY_TO_COMMIT,
            validation = merged,
        )
        return merged.errors.isEmpty()
    }

    fun confirmWarnings() = editWithoutInvalidating { current ->
        preparedRequest = preparedRequest?.copy(warningsConfirmed = true)
        current.copy(warningsConfirmed = true)
    }

    fun requestDiscardConfirmation() = editWithoutInvalidating { it.copy(showDiscardConfirmation = true) }

    fun keepEditing() = editWithoutInvalidating { it.copy(showDiscardConfirmation = false) }

    suspend fun submit(): Boolean {
        if (mutablePending.value) return false
        val current = mutableState.value ?: return false
        val request = preparedRequest ?: return false
        if (current.validation.errors.isNotEmpty() || current.validation.warnings.isNotEmpty() && !current.warningsConfirmed) return false
        mutablePending.value = true
        mutableState.value = current.copy(presentation = BatchRecordPresentation.COMMITTING)
        return try {
            when (val result = application.submit(request.copy(warningsConfirmed = current.warningsConfirmed))) {
                is DomainResult.Success -> {
                    preparedRequest = null
                    mutableState.value = current.copy(
                        presentation = BatchRecordPresentation.COMMITTED,
                        committedBatchCommandId = result.value.receipt.commandId.stableId,
                    )
                    true
                }
                is DomainResult.Failure -> {
                    mutableState.value = current.copy(
                        presentation = BatchRecordPresentation.COMMIT_FAILED,
                        sanitizedFailureCode = sanitizeCode(result.error.code),
                    )
                    false
                }
            }
        } finally {
            mutablePending.value = false
        }
    }

    suspend fun undo(): Boolean {
        if (mutablePending.value) return false
        val current = mutableState.value ?: return false
        val originalCommand = current.committedBatchCommandId?.let(::CommandId) ?: return false
        val audit = when (val result = application.audit(current.snapshot.references.bookId, originalCommand)) {
            is DomainResult.Success -> result.value ?: return false
            is DomainResult.Failure -> return false
        }
        val request = BatchUndoRequest(
            current.snapshot.references.bookId,
            originalCommand,
            CommandId(nextId()),
            nextId(),
            nextId(),
            runtime.clock.now(),
            audit.transactionIds.map { transactionId ->
                BatchUndoRowIds(transactionId, nextId(), List(FACT_ID_RESERVE) { nextId() })
            },
        )
        mutablePending.value = true
        return try {
            application.undo(request) is DomainResult.Success
        } finally {
            mutablePending.value = false
        }
    }

    fun discard() {
        preparedRequest = null
        refundSnapshot = null
        installmentSnapshot = null
        mutableState.value = null
    }

    private fun buildRequest(state: BatchRecordState, warningsConfirmed: Boolean): BatchEntrySubmitRequest? = runCatching {
        val bookId = state.snapshot.references.bookId
        val createdAt = runtime.clock.now()
        val parentCommandId = CommandId(nextId())
        val commitId = nextId()
        val deviceId = nextId()
        val rows = state.rows.map { row ->
            when (row.kind) {
                BatchRowKind.EXPENSE, BatchRowKind.INCOME -> ordinaryRequest(row, bookId, commitId, deviceId, createdAt, parentCommandId)
                BatchRowKind.REFUND -> refundRequest(row, bookId, commitId, deviceId, createdAt)
            }
        }
        BatchEntrySubmitRequest(bookId, parentCommandId, commitId, deviceId, createdAt, rows, warningsConfirmed)
    }.getOrNull()

    private fun ordinaryRequest(
        row: BatchRowDraft,
        bookId: StableId,
        commitId: StableId,
        deviceId: StableId,
        createdAt: java.time.Instant,
        parentCommandId: CommandId,
    ): BatchEntryRowWriteRequest {
        val direction = if (row.kind == BatchRowKind.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE
        val currency = requireNotNull(CurrencyCode.parse(row.userCurrencyCode).getOrNull())
        val ids = OrdinaryTransactionWriteIds(
            bookId,
            nextId(),
            nextId(),
            nextId(),
            commitId,
            deviceId,
            List(FACT_ID_RESERVE) { nextId() },
            List(FX_ID_RESERVE) { nextId() },
        )
        return BatchEntryRowWriteRequest.Ordinary(
            row.rowId,
            OrdinaryTransactionWriteRequest(
                ids,
                null,
                direction,
                requireNotNull(row.categoryId),
                OrdinaryAmountDraft(row.amountExpression, requireNotNull(row.userMinor), currency, requireNotNull(row.accountMinor), requireNotNull(row.baseMinor)),
                row.accountId,
                row.cardId,
                row.merchantId,
                row.occurredAt,
                row.zoneId,
                row.occurredAt.atZone(row.zoneId).toLocalDate(),
                row.projectId,
                null,
                row.settlementActivityId,
                row.settlementShares,
                row.locationRecordId,
                null,
                row.note.trim().takeIf(String::isNotEmpty),
                row.attachmentIds,
                TransactionSource.BATCH_OPERATION,
                parentCommandId.stableId,
                createdAt,
                installmentPlanId = row.installmentPlanId,
            ),
        )
    }

    private fun refundRequest(
        row: BatchRowDraft,
        bookId: StableId,
        commitId: StableId,
        deviceId: StableId,
        createdAt: java.time.Instant,
    ): BatchEntryRowWriteRequest {
        val snapshot = requireNotNull(refundSnapshot)
        val original = row.refundOriginalTransactionId?.let { id -> snapshot.originals.single { it.transactionId == id } }
        val account = snapshot.references.accounts.single { it.id == row.accountId }
        val inputCurrency = requireNotNull(CurrencyCode.parse(row.userCurrencyCode).getOrNull())
        val inputMinor = requireNotNull(row.userMinor)
        val accountMinor = requireNotNull(row.accountMinor)
        val baseMinor = requireNotNull(row.baseMinor)
        val allocation = original?.let {
            RefundAllocationDraft(it.transactionId, it.revisionId, inputMinor, proportionalBase(inputMinor, it.originalMinor, it.originalBaseMinor))
        }
        val localDate = row.occurredAt.atZone(row.zoneId).toLocalDate()
        val ids = RefundWriteIds(
            bookId,
            CommandId(nextId()),
            nextId(),
            nextId(),
            commitId,
            deviceId,
            List(FACT_ID_RESERVE) { nextId() },
            List(FX_ID_RESERVE) { nextId() },
        )
        return BatchEntryRowWriteRequest.Refund(
            row.rowId,
            RefundWriteRequest(
                ids,
                listOfNotNull(allocation),
                RefundAmountDraft(
                    inputMinor,
                    inputCurrency,
                    requireNotNull(row.accountId),
                    accountMinor,
                    baseMinor,
                    impliedEvidence(inputMinor, inputCurrency, accountMinor, account.currency, createdAt),
                    impliedEvidence(accountMinor, account.currency, baseMinor, snapshot.references.baseCurrency, createdAt),
                ),
                row.cardId,
                original == null,
                row.categoryId,
                row.merchantId,
                row.projectId,
                null,
                original?.settlementActivityId,
                original?.settlementShares.orEmpty(),
                row.occurredAt,
                row.zoneId,
                localDate,
                localDate,
                YearMonth.from(localDate),
                RefundBudgetPolicy.RESTORE_REFUND_MONTH,
                if (row.projectId == null) RefundProjectPolicy.DO_NOT_RESTORE else RefundProjectPolicy.USE_SELECTED_PROJECT,
                RefundGoalPolicy.DO_NOT_RESTORE,
                RefundAccrualPolicy.REFUND_DATE,
                false,
                false,
                row.amountExpression,
                row.note.trim().takeIf(String::isNotEmpty),
                row.attachmentIds,
                createdAt,
                row.locationRecordId,
            ),
        )
    }

    private fun impliedEvidence(
        sourceMinor: Long,
        sourceCurrency: CurrencyCode,
        targetMinor: Long,
        targetCurrency: CurrencyCode,
        recordedAt: Instant,
    ): FxEvidence? {
        if (sourceCurrency == targetCurrency) {
            require(sourceMinor == targetMinor)
            return null
        }
        val sourceMetadata = requireNotNull(currencies.find(sourceCurrency))
        val targetMetadata = requireNotNull(currencies.find(targetCurrency))
        val sourceMajor = BigDecimal.valueOf(sourceMinor, sourceMetadata.fractionDigits)
        val targetMajor = BigDecimal.valueOf(targetMinor, targetMetadata.fractionDigits)
        val rate = targetMajor.divide(sourceMajor, java.math.MathContext(34, RoundingMode.HALF_EVEN))
        return requireNotNull(
            FxEvidence.create(
                FxEvidenceInput(
                    sourceCurrency,
                    targetCurrency,
                    rate,
                    batchFxProvider,
                    recordedAt,
                    recordedAt,
                    FxRateSource.IMPLIED_FROM_ACTUAL_AMOUNTS,
                    true,
                ),
            ).getOrNull(),
        )
    }

    private fun cycleRow(row: BatchRowDraft, state: BatchRecordState, field: BatchEntryField): BatchRowDraft = when (field) {
        BatchEntryField.CATEGORY -> {
            val direction = if (row.kind == BatchRowKind.INCOME) CategoryDirection.INCOME else CategoryDirection.EXPENSE
            row.copy(categoryId = nextNullable(state.snapshot.references.categories.filter { it.direction == direction && it.status.name == "ACTIVE" }.map { it.id }, row.categoryId, includeNone = false))
        }
        BatchEntryField.ACCOUNT_AND_CARD -> {
            val accounts = state.snapshot.references.accounts.filter { it.status == EntityStatus.ACTIVE }.map { it.id }
            val cards = state.snapshot.references.cards.filter { it.status == EntityStatus.ACTIVE && it.accountId == row.accountId }
            if (row.cardId == null && cards.isNotEmpty()) {
                row.copy(cardId = cards.first().id)
            } else {
                val cardIndex = cards.indexOfFirst { it.id == row.cardId }
                if (cardIndex >= 0 && cardIndex < cards.lastIndex) {
                    row.copy(cardId = cards[cardIndex + 1].id)
                } else {
                    val accountId = nextNullable(accounts, row.accountId, includeNone = false)
                    val account = state.snapshot.references.accounts.singleOrNull { it.id == accountId }
                    val currencyCode = account?.currency?.value ?: row.userCurrencyCode
                    val amountMinor = BatchRecordPolicy.majorToMinor(row.amountExpression, currencyCode)
                    row.copy(
                        accountId = accountId,
                        cardId = null,
                        userCurrencyCode = currencyCode,
                        userMinor = amountMinor,
                        accountMinor = amountMinor,
                        baseMinor = amountMinor.takeIf { account?.currency == state.snapshot.references.baseCurrency },
                    )
                }
            }
        }
        BatchEntryField.MERCHANT -> row.copy(merchantId = nextNullable(state.snapshot.references.merchants.filter { it.status == EntityStatus.ACTIVE }.map { it.id }, row.merchantId))
        BatchEntryField.PROJECT -> row.copy(projectId = nextNullable(state.snapshot.projects.filter { it.active }.map { it.id }, row.projectId))
        BatchEntryField.SETTLEMENT -> {
            val activityId = nextNullable(state.snapshot.settlementActivities.filter { it.active }.map { it.id }, row.settlementActivityId)
            val activity = state.snapshot.settlementActivities.singleOrNull { it.id == activityId }
            val total = row.userMinor
            val shares = if (row.kind == BatchRowKind.EXPENSE && activity != null && total != null && total > 0L && activity.participants.isNotEmpty()) {
                val base = total / activity.participants.size
                var remainder = total % activity.participants.size
                activity.participants.map { participant ->
                    val owed = base + if (remainder-- > 0L) 1L else 0L
                    OrdinarySettlementShareDraft(
                        participant.id,
                        if (participant.isSelf) total else 0L,
                        owed,
                        null,
                        0L,
                    )
                }
            } else {
                emptyList()
            }
            row.copy(settlementActivityId = activityId, settlementShares = shares)
        }
        BatchEntryField.LOCATION -> row.copy(locationRecordId = nextNullable(state.snapshot.references.locations.map { it.id }, row.locationRecordId))
        BatchEntryField.INSTALLMENT -> row.copy(installmentPlanId = nextNullable(installmentSnapshot?.plans.orEmpty().map { it.id }, row.installmentPlanId))
        BatchEntryField.REFUND_RELATION -> {
            val originalId = nextNullable(refundSnapshot?.originals.orEmpty().map { it.transactionId }, row.refundOriginalTransactionId)
            val original = refundSnapshot?.originals?.singleOrNull { it.transactionId == originalId }
            row.copy(
                refundOriginalTransactionId = originalId,
                categoryId = original?.categoryId ?: row.categoryId,
                merchantId = original?.merchantId ?: row.merchantId,
                projectId = original?.projectId ?: row.projectId,
                installmentPlanId = original?.installmentPlanId ?: row.installmentPlanId,
            )
        }
        else -> row
    }

    private fun <T> nextNullable(values: List<T>, current: T?, includeNone: Boolean = true): T? {
        if (values.isEmpty()) return null
        val choices = if (includeNone) listOf<T?>(null) + values else values.map { it as T? }
        val index = choices.indexOf(current)
        return choices[(if (index < 0) 0 else index + 1) % choices.size]
    }

    private fun edit(block: (BatchRecordState) -> BatchRecordState) {
        preparedRequest = null
        editWithoutInvalidating { block(it).copy(presentation = BatchRecordPresentation.EDITING, validation = BatchValidationReport(emptyList()), warningsConfirmed = false, sanitizedFailureCode = null) }
    }

    private fun editWithoutInvalidating(block: (BatchRecordState) -> BatchRecordState) {
        mutableState.value = mutableState.value?.let(block)
    }

    private fun proportionalBase(amount: Long, original: Long, originalBase: Long): Long = BigDecimal.valueOf(amount)
        .multiply(BigDecimal.valueOf(originalBase))
        .divide(BigDecimal.valueOf(original), 0, RoundingMode.HALF_EVEN)
        .longValueExact()

    private fun nextId(): StableId = runtime.stableIds.nextStableId()

    private fun sanitizeCode(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48)

    private companion object {
        const val FACT_ID_RESERVE = 128
        const val FX_ID_RESERVE = 8
    }
}
