@file:Suppress("LongMethod", "ReturnCount")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.LedgerExportCursor
import app.ledger.finance.application.LedgerExportQueryPort
import app.ledger.finance.application.LedgerWorkbookSheet
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.GeoPoint
import app.ledger.finance.domain.GeoRadiusFilter
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionAmountRange
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportFailure
import app.ledger.transfer.domain.ExportMetadata
import app.ledger.transfer.domain.ExportTablePage
import app.ledger.transfer.domain.ExportTabularSource
import java.time.Instant

class LedgerExportTabularSource(
    private val bookId: StableId,
    private val descriptor: ExportDescriptor,
    private val query: LedgerExportQueryPort,
    private val generatedAt: Instant,
    private val applicationVersion: String,
) : ExportTabularSource {
    private val cursors = mutableMapOf<Long, LedgerExportCursor>()

    override suspend fun metadata(): DomainResult<ExportMetadata> = when (descriptor.content) {
        ExportContent.REPORT -> descriptor.report?.let { report ->
            DomainResult.Success(
                ExportMetadata(
                    1,
                    applicationVersion,
                    generatedAt,
                    descriptor.content,
                    descriptor.filterSummary,
                    report.localRevision,
                    report.valuationRevision,
                ),
            )
        } ?: DomainResult.Failure(ExportFailure.SourceUnavailable)
        else -> when (val loaded = query.metadata(bookId)) {
            is DomainResult.Success -> DomainResult.Success(
                ExportMetadata(
                    1,
                    applicationVersion,
                    generatedAt,
                    descriptor.content,
                    descriptor.filterSummary,
                    loaded.value.localRevision,
                    loaded.value.valuationRevision,
                ),
            )
            is DomainResult.Failure -> DomainResult.Failure(ExportFailure.SourceUnavailable)
        }
    }

    override suspend fun sheetNames(): DomainResult<List<String>> = DomainResult.Success(
        when (descriptor.content) {
            ExportContent.CURRENT_FILTER -> listOf("transactions")
            ExportContent.FULL_WORKBOOK -> LedgerWorkbookSheet.entries.map(LedgerWorkbookSheet::worksheetName)
            ExportContent.REPORT -> listOf("report")
        },
    )

    override suspend fun page(sheetName: String, afterKey: Long?, limit: Int): DomainResult<ExportTablePage> = when (descriptor.content) {
        ExportContent.CURRENT_FILTER -> currentPage(sheetName, afterKey, limit)
        ExportContent.FULL_WORKBOOK -> workbookPage(sheetName, afterKey, limit)
        ExportContent.REPORT -> reportPage(sheetName, afterKey, limit)
    }

    private suspend fun currentPage(sheetName: String, afterKey: Long?, limit: Int): DomainResult<ExportTablePage> {
        if (sheetName != "transactions") return DomainResult.Failure(ExportFailure.SourceUnavailable)
        val requestedCursor = afterKey?.let(cursors::get)
        if (afterKey != null && requestedCursor == null) return DomainResult.Failure(ExportFailure.CorruptCheckpoint)
        val headers = descriptor.fields.sortedBy(Enum<*>::ordinal).map { it.header }
        return when (val page = query.currentTransactions(bookId, descriptor.filter.toDomain(), headers, requestedCursor, limit)) {
            is DomainResult.Failure -> DomainResult.Failure(ExportFailure.SourceUnavailable)
            is DomainResult.Success -> {
                val nextKey = page.value.nextCursor?.let { cursor ->
                    val key = Math.addExact(afterKey ?: 0L, 1L)
                    cursors[key] = cursor
                    key
                }
                DomainResult.Success(ExportTablePage(page.value.headers, page.value.rows, nextKey))
            }
        }
    }

    private suspend fun workbookPage(sheetName: String, afterKey: Long?, limit: Int): DomainResult<ExportTablePage> {
        val sheet = LedgerWorkbookSheet.entries.singleOrNull { it.worksheetName == sheetName }
            ?: return DomainResult.Failure(ExportFailure.SourceUnavailable)
        return when (val page = query.workbookSheet(bookId, sheet, descriptor.includeLocationCoordinates, afterKey ?: 0L, limit)) {
            is DomainResult.Failure -> DomainResult.Failure(ExportFailure.SourceUnavailable)
            is DomainResult.Success -> DomainResult.Success(
                ExportTablePage(page.value.headers, page.value.rows, page.value.nextCursor?.orderValue),
            )
        }
    }

    private fun reportPage(sheetName: String, afterKey: Long?, limit: Int): DomainResult<ExportTablePage> {
        val report = descriptor.report ?: return DomainResult.Failure(ExportFailure.SourceUnavailable)
        if (sheetName != "report" || limit <= 0) return DomainResult.Failure(ExportFailure.SourceUnavailable)
        val start = (afterKey ?: 0L).toInt()
        if (start !in 0..report.rows.size) return DomainResult.Failure(ExportFailure.CorruptCheckpoint)
        val end = (start + limit).coerceAtMost(report.rows.size)
        return DomainResult.Success(
            ExportTablePage(report.headers, report.rows.subList(start, end), end.toLong().takeIf { end < report.rows.size }),
        )
    }
}

private fun app.ledger.transfer.domain.ExportFilter.toDomain(): TransactionFilter {
    val amount = if (minimumAccountMinor != null || maximumAccountMinor != null) {
        TransactionAmountRange(
            minimumAccountMinor,
            maximumAccountMinor,
            amountCurrency?.let { CurrencyCode.parse(it).success() },
        )
    } else {
        null
    }
    val geo = centerLatitudeE7?.let { latitude ->
        GeoRadiusFilter(GeoPoint.create(latitude, requireNotNull(centerLongitudeE7)).success(), requireNotNull(radiusMeters))
    }
    return TransactionFilter(
        occurredFrom = occurredFrom,
        occurredThrough = occurredThrough,
        createdFrom = createdFrom,
        createdThrough = createdThrough,
        modifiedFrom = modifiedFrom,
        modifiedThrough = modifiedThrough,
        kinds = kinds.mapTo(mutableSetOf()) { TransactionKind.entries[it] },
        accountIds = accountIds.mapTo(mutableSetOf(), ::UserAccountId),
        cardIds = cardIds.mapTo(mutableSetOf(), ::PaymentCardId),
        categoryIds = categoryIds.mapTo(mutableSetOf(), ::CategoryId),
        merchantIds = merchantIds.mapTo(mutableSetOf(), ::MerchantId),
        projectIds = projectIds.mapTo(mutableSetOf(), ::ProjectId),
        settlementActivityIds = settlementActivityIds.mapTo(mutableSetOf(), ::SettlementActivityId),
        participantIds = participantIds.mapTo(mutableSetOf(), ::ParticipantId),
        currencies = currencies.mapTo(mutableSetOf()) { CurrencyCode.parse(it).success() },
        statisticalNatures = statisticalNatures.mapTo(mutableSetOf()) { StatisticalNature.entries[it] },
        amountRange = amount,
        geoRadius = geo,
        hasAttachment = hasAttachment,
        isRefund = isRefund,
        hasInstallment = hasInstallment,
        includedInBudget = includedInBudget,
        generatedByRecurrence = generatedByRecurrence,
        sources = sources.mapTo(mutableSetOf()) { TransactionSource.entries[it] },
        lifecycleStates = lifecycleStates.mapTo(mutableSetOf()) { TransactionLifecycleState.entries[it] },
        searchText = searchText,
    )
}

private fun <T> DomainResult<T>.success(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> error(error.code)
}
