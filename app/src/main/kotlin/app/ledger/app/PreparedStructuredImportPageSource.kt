@file:Suppress("MagicNumber")

package app.ledger.app

import app.ledger.core.common.DomainResult
import app.ledger.finance.application.StructuredImportEntityType
import app.ledger.finance.application.StructuredImportPageSource
import app.ledger.finance.application.StructuredImportPhase
import app.ledger.finance.application.StructuredImportRow
import app.ledger.finance.application.StructuredImportValues
import app.ledger.transfer.data.PreparedImportPayloadDecoder
import app.ledger.transfer.data.SqlCipherStagingRepository
import app.ledger.transfer.domain.PreparedCommandValidationState

/** Streams dependency-ordered entity commands from encrypted staging without building a workbook graph in memory. */
internal class PreparedStructuredImportPageSource(
    private val staging: SqlCipherStagingRepository,
) : StructuredImportPageSource {
    override suspend fun load(
        phase: StructuredImportPhase,
        afterRowOrdinal: Long,
        maximumRows: Int,
    ): DomainResult<List<StructuredImportRow>> {
        require(afterRowOrdinal >= 0L)
        require(maximumRows in 1..512)
        return when (
            val loaded = staging.structuredPreparedCommands(
                afterRowOrdinal,
                maximumRows,
                phase == StructuredImportPhase.BEFORE_TRANSACTIONS,
            )
        ) {
            is DomainResult.Success -> DomainResult.Success(
                loaded.value.mapNotNull { prepared ->
                    if (prepared.validationState != PreparedCommandValidationState.DOMAIN_VALIDATED) return@mapNotNull null
                    val kind = requireNotNull(prepared.structuredKind)
                    val payload = PreparedImportPayloadDecoder.decode(prepared.payload)
                    require(payload.type == kind.canonicalSheetName)
                    StructuredImportRow(
                        prepared.rowNumber,
                        StructuredImportEntityType.valueOf(kind.name),
                        StructuredImportValues(payload.values),
                    )
                },
            )
            is DomainResult.Failure -> loaded
        }
    }
}
