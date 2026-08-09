package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.TransactionId

data class EntityMappingDecision(
    val targetField: ImportTargetField,
    val sourceValue: String,
    val existingEntityId: StableId?,
    val createMissing: Boolean,
) {
    init {
        require(sourceValue.isNotBlank())
        require((existingEntityId != null) xor createMissing)
        require(targetField in ENTITY_FIELDS)
    }

    private companion object {
        val ENTITY_FIELDS = setOf(
            ImportTargetField.CATEGORY,
            ImportTargetField.ACCOUNT,
            ImportTargetField.CARD,
            ImportTargetField.MERCHANT,
            ImportTargetField.PROJECT,
            ImportTargetField.LOCATION,
            ImportTargetField.PAYER,
            ImportTargetField.PAYEE,
        )
    }
}

enum class MissingFxPolicy { REQUIRE_MANUAL_RATE, USE_PROVIDED_RATE }

data class FxImportDecision(
    val sourceCurrency: String,
    val targetCurrency: String,
    val policy: MissingFxPolicy,
    val rate: java.math.BigDecimal?,
) {
    init {
        require(sourceCurrency.matches(Regex("[A-Z]{3}")))
        require(targetCurrency.matches(Regex("[A-Z]{3}")))
        require((policy == MissingFxPolicy.USE_PROVIDED_RATE) == (rate != null))
        require(rate == null || rate > java.math.BigDecimal.ZERO)
    }
}

enum class DuplicateResolution { SKIP, IMPORT_ANYWAY }

data class ImportPreparationRequest(
    val baseCurrency: String,
    val mappings: List<StagingMapping>,
    val entityDecisions: List<EntityMappingDecision>,
    val fxDecisions: List<FxImportDecision>,
    val duplicateResolutions: Map<Long, DuplicateResolution>,
    val includedSheets: Set<String> = emptySet(),
) {
    init {
        require(baseCurrency.matches(Regex("[A-Z]{3}")))
        require(mappings.map { it.sourceColumn to it.targetField }.toSet().size == mappings.size)
        require(entityDecisions.map { it.targetField to it.sourceValue }.toSet().size == entityDecisions.size)
        require(duplicateResolutions.keys.all { it > 0L })
        require(includedSheets.none(String::isBlank))
    }
}

fun interface ExistingTransactionMatcher {
    suspend fun find(rowNumber: Long, canonicalPayloadHash: app.ledger.finance.domain.Hash256): DomainResult<DuplicateMatch?>
}

data class DuplicateMatch(
    val transactionId: TransactionId,
    val kind: DuplicateMatchKind,
    val confidenceCode: String,
) {
    init {
        require(confidenceCode.matches(Regex("[A-Z0-9_]{2,80}")))
    }
}

data class ImportPreparationResult(
    val report: ImportValidationReport,
    val preparedRows: Long,
    val duplicateRows: Long,
    val missingEntitiesToCreate: Long,
)

class PreparedImportPayload(
    val type: String,
    values: Map<String, String>,
) {
    private val storedValues = values.toMap()
    val values: Map<String, String> get() = storedValues

    init {
        require(type.matches(Regex("[a-z_]{2,40}")))
        require(storedValues.isNotEmpty())
        require(storedValues.keys.none(String::isBlank))
    }

    override fun toString(): String = "PreparedImportPayload(type=$type,values=redacted,count=${storedValues.size})"
}
