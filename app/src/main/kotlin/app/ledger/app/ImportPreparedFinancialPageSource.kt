@file:Suppress("MagicNumber", "LongMethod", "LongParameterList", "ReturnCount")

package app.ledger.app

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.BatchEntryRowWriteRequest
import app.ledger.finance.application.BatchEntrySubmitRequest
import app.ledger.finance.application.ImportFinancialPage
import app.ledger.finance.application.ImportFinancialPageSource
import app.ledger.finance.application.ImportSourceRow
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.TransactionSource
import app.ledger.transfer.data.PreparedImportPayloadDecoder
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.EncryptedStagingRepository
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.PreparedCommandValidationState
import app.ledger.transfer.domain.StagingPreparedCommand
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

/** Materializes validated staging envelopes into the same typed P24 batch boundary used by manual batch entry. */
internal class ImportPreparedFinancialPageSource(
    private val bookId: StableId,
    private val operationId: BackgroundOperationId,
    private val batchId: StableId,
    private val format: ImportFormat,
    private val baseCurrency: CurrencyCode,
    private val zoneId: ZoneId,
    private val requestedAt: Instant,
    private val staging: EncryptedStagingRepository,
) : ImportFinancialPageSource {
    override suspend fun load(afterRowNumber: Long, maximumRows: Int): DomainResult<ImportFinancialPage?> {
        require(afterRowNumber >= 0L)
        require(maximumRows in 1..5_000)
        val selected = mutableListOf<StagingPreparedCommand>()
        var validatedOrdinal = 0L
        var sourceOffset = 0L
        while (selected.size <= maximumRows) {
            val page = when (val loaded = staging.preparedCommands(sourceOffset, STAGING_PAGE_ROWS)) {
                is DomainResult.Success -> loaded.value
                is DomainResult.Failure -> return loaded
            }
            if (page.isEmpty()) break
            page.forEach { command ->
                if (command.commandType == null) return@forEach
                if (command.validationState != PreparedCommandValidationState.DOMAIN_VALIDATED) return@forEach
                validatedOrdinal++
                if (validatedOrdinal > afterRowNumber && selected.size <= maximumRows) selected += command
            }
            sourceOffset = page.last().rowNumber
        }
        if (selected.isEmpty()) return DomainResult.Success(null)
        val hasMore = selected.size > maximumRows
        val commands = selected.take(maximumRows)
        val firstOrdinal = afterRowNumber + 1L
        val lastOrdinal = afterRowNumber + commands.size
        val commitId = derived("page:$firstOrdinal:commit")
        val deviceId = derived("page:$firstOrdinal:device")
        val rows = mutableListOf<BatchEntryRowWriteRequest>()
        val sourceRows = mutableListOf<ImportSourceRow>()
        commands.forEach { prepared ->
            val row = materialize(prepared, commitId, deviceId)
                ?: return DomainResult.Failure(ImportFailure.UnsupportedSource)
            rows += row
            sourceRows += ImportSourceRow(prepared.rowNumber, row.transactionId, prepared.payloadHash)
        }
        val pageCommand = if (firstOrdinal == 1L) batchId else derived("page:$firstOrdinal:batch")
        return DomainResult.Success(
            ImportFinancialPage(
                firstOrdinal,
                lastOrdinal,
                BatchEntrySubmitRequest(bookId, CommandId(pageCommand), commitId, deviceId, requestedAt, rows, warningsConfirmed = true),
                sourceRows,
                lastPage = !hasMore,
            ),
        )
    }

    private fun materialize(
        prepared: StagingPreparedCommand,
        commitId: StableId,
        deviceId: StableId,
    ): BatchEntryRowWriteRequest? {
        val direction = when (prepared.commandType) {
            FinancialCommandType.RECORD_EXPENSE -> OrdinaryDirection.EXPENSE
            FinancialCommandType.RECORD_INCOME -> OrdinaryDirection.INCOME
            else -> return null
        }
        val payload = PreparedImportPayloadDecoder.decode(prepared.payload)
        if (payload.type != "transaction") return null
        val values = payload.values
        val currencyText = values[ImportTargetField.CURRENCY.name] ?: return null
        val currency = CurrencyCode.parse(currencyText).getOrNull() ?: return null
        val scale = Currency.getInstance(currencyText).defaultFractionDigits.takeIf { it >= 0 } ?: return null
        val expression = values[ImportTargetField.AMOUNT_EXPRESSION.name] ?: return null
        val userMinor = expression.toBigDecimalOrNull()?.movePointRight(scale)?.setScale(0, RoundingMode.UNNECESSARY)?.longValueExact()
            ?: return null
        val baseMinor = if (currency == baseCurrency) {
            userMinor
        } else {
            val baseScale = Currency.getInstance(baseCurrency.value).defaultFractionDigits.takeIf { it >= 0 } ?: return null
            val rate = values[ImportTargetField.FX_RATE.name]?.toBigDecimalOrNull() ?: return null
            expression.toBigDecimal().multiply(rate).movePointRight(baseScale).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        }
        val occurredAt = parseInstant(values[ImportTargetField.OCCURRED_AT.name] ?: return null) ?: return null
        val prefix = "row:${prepared.rowNumber}"
        val ids = OrdinaryTransactionWriteIds(
            bookId,
            derived("$prefix:command"),
            derived("$prefix:transaction"),
            derived("$prefix:revision"),
            commitId,
            deviceId,
            List(FACT_ID_CAPACITY) { index -> derived("$prefix:fact:$index") },
            List(FX_ID_CAPACITY) { index -> derived("$prefix:fx:$index") },
        )
        val request = OrdinaryTransactionWriteRequest(
            ids,
            null,
            direction,
            parseId(values[ImportTargetField.CATEGORY.name], ImportTargetField.CATEGORY) ?: return null,
            OrdinaryAmountDraft(expression, userMinor, currency, userMinor, baseMinor),
            parseId(values[ImportTargetField.ACCOUNT.name], ImportTargetField.ACCOUNT) ?: return null,
            parseId(values[ImportTargetField.CARD.name], ImportTargetField.CARD),
            parseId(values[ImportTargetField.MERCHANT.name], ImportTargetField.MERCHANT),
            occurredAt,
            zoneId,
            occurredAt.atZone(zoneId).toLocalDate(),
            parseId(values[ImportTargetField.PROJECT.name], ImportTargetField.PROJECT),
            null,
            null,
            emptyList(),
            parseId(values[ImportTargetField.LOCATION.name], ImportTargetField.LOCATION),
            null,
            values[ImportTargetField.NOTE.name]?.takeIf(String::isNotBlank),
            emptyList(),
            source(),
            operationId.value,
            requestedAt,
        )
        return BatchEntryRowWriteRequest.Ordinary(derived("$prefix:row"), request)
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDate.parse(value).atStartOfDay(zoneId).toInstant() }.getOrNull()

    private fun parseId(value: String?, field: ImportTargetField): StableId? = when {
        value == null -> null
        value.startsWith("create:") -> derivedMissing(field, value.removePrefix("create:"))
        else -> StableId.parse(value).getOrNull()
    }

    private fun derivedMissing(field: ImportTargetField, value: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256")
            .digest(operationId.value.bytes + field.name.toByteArray(Charsets.US_ASCII) + value.toByteArray(Charsets.UTF_8))
            .copyOf(StableId.BYTE_COUNT),
    ).getOrNull() ?: error("deterministic missing entity id is invalid")

    private fun source(): TransactionSource = when (format) {
        ImportFormat.CSV -> TransactionSource.CSV_IMPORT
        ImportFormat.XLSX -> TransactionSource.XLSX_IMPORT
        ImportFormat.STRUCTURED_WORKBOOK -> TransactionSource.STRUCTURED_IMPORT
        ImportFormat.FULL_BACKUP -> error("backup is not a row import")
    }

    private fun derived(label: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256")
            .digest(operationId.value.bytes + label.toByteArray(Charsets.US_ASCII))
            .copyOf(StableId.BYTE_COUNT),
    ).getOrNull() ?: error("deterministic import id is invalid")

    private companion object {
        const val STAGING_PAGE_ROWS = 512
        const val FACT_ID_CAPACITY = 251
        const val FX_ID_CAPACITY = 8
    }
}
