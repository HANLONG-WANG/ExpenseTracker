@file:Suppress("NestedBlockDepth")

package app.ledger.app

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.finance.application.StructuredImportEntityType
import app.ledger.finance.application.StructuredImportPageSource
import app.ledger.finance.application.StructuredImportPhase
import app.ledger.finance.application.StructuredImportRow
import app.ledger.finance.application.StructuredImportValues
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.transfer.data.PreparedImportPayloadDecoder
import app.ledger.transfer.data.SqlCipherStagingRepository
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.ImportTransformation
import java.security.MessageDigest

/** Rebuilds missing reference-data writes only from the encrypted preparation snapshot. */
internal object PreparedGeneralMissingEntitySource {
    suspend fun rows(
        staging: SqlCipherStagingRepository,
        operationId: StableId,
        baseCurrency: String,
        sourceRowNumber: Long,
    ): DomainResult<List<StructuredImportRow>> = try {
        val mappings = when (val loaded = staging.mappings()) {
            is DomainResult.Success -> loaded.value
            is DomainResult.Failure -> return loaded
        }
        val accountCurrencies = linkedMapOf<String, MutableSet<String>>()
        val categoryDirections = linkedMapOf<String, MutableSet<String>>()
        var after = 0L
        while (true) {
            val commands = when (val loaded = staging.preparedCommands(after, PAGE_ROWS)) {
                is DomainResult.Success -> loaded.value
                is DomainResult.Failure -> return loaded
            }
            if (commands.isEmpty()) break
            commands.filter { it.commandType != null }.forEach { command ->
                val values = PreparedImportPayloadDecoder.decode(command.payload).values
                values[ImportTargetField.ACCOUNT.name]?.removePrefix(CREATE_PREFIX)
                    ?.takeIf { values[ImportTargetField.ACCOUNT.name]?.startsWith(CREATE_PREFIX) == true }
                    ?.let { name ->
                        values[ImportTargetField.CURRENCY.name]?.let {
                            accountCurrencies.getOrPut(name, ::linkedSetOf).add(it)
                        }
                    }
                values[ImportTargetField.CATEGORY.name]?.removePrefix(CREATE_PREFIX)
                    ?.takeIf { values[ImportTargetField.CATEGORY.name]?.startsWith(CREATE_PREFIX) == true }
                    ?.let { name ->
                        val direction = when (command.commandType) {
                            FinancialCommandType.RECORD_INCOME -> "INCOME"
                            FinancialCommandType.RECORD_EXPENSE -> "EXPENSE"
                            else -> null
                        }
                        direction?.let { categoryDirections.getOrPut(name, ::linkedSetOf).add(it) }
                    }
            }
            after = commands.last().rowNumber
        }
        if (accountCurrencies.values.any { it.size > 1 } || categoryDirections.values.any { it.size > 1 }) {
            return DomainResult.Failure(ImportFailure.ValidationFailed)
        }
        val creates = mappings.mapNotNull { mapping ->
            val closed = mapping.transformation as? ImportTransformation.ClosedValueMap ?: return@mapNotNull null
            if (mapping.targetField !in CREATABLE_FIELDS) return@mapNotNull null
            closed.entries.filterValues { it.startsWith(CREATE_PREFIX) }.keys.map { mapping.targetField to it }
        }.flatten().distinct().sortedWith(compareBy({ it.first.ordinal }, { it.second }))
        DomainResult.Success(
            creates.map { (field, name) ->
                val id = derived(operationId, field, name)
                val type: StructuredImportEntityType
                val values = when (field) {
                    ImportTargetField.ACCOUNT -> {
                        type = StructuredImportEntityType.ACCOUNT
                        mapOf(
                            "id" to id.toString(),
                            "ledger_account_id" to derived(operationId, field, "ledger:$name").toString(),
                            "name" to name,
                            "currency" to (accountCurrencies[name]?.singleOrNull() ?: baseCurrency),
                            "type" to "BANK",
                        )
                    }
                    ImportTargetField.CATEGORY -> {
                        type = StructuredImportEntityType.CATEGORY
                        mapOf(
                            "id" to id.toString(),
                            "name" to name,
                            "direction" to (categoryDirections[name]?.singleOrNull() ?: "EXPENSE"),
                        )
                    }
                    ImportTargetField.MERCHANT -> {
                        type = StructuredImportEntityType.MERCHANT
                        mapOf("id" to id.toString(), "name" to name)
                    }
                    else -> error("unsupported missing reference type")
                }
                StructuredImportRow(sourceRowNumber, type, StructuredImportValues(values))
            },
        )
    } catch (_: Exception) {
        DomainResult.Failure(ImportFailure.ValidationFailed)
    }

    fun pageSource(rows: List<StructuredImportRow>) = StructuredImportPageSource { phase, offset, limit ->
        require(offset >= 0L)
        require(limit in 1..PAGE_ROWS)
        val selected = if (phase == StructuredImportPhase.BEFORE_TRANSACTIONS) rows else emptyList()
        DomainResult.Success(selected.drop(offset.toInt()).take(limit))
    }

    private fun derived(operationId: StableId, field: ImportTargetField, value: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256")
            .digest(operationId.bytes + field.name.toByteArray(Charsets.US_ASCII) + value.toByteArray(Charsets.UTF_8))
            .copyOf(StableId.BYTE_COUNT),
    ).getOrNull() ?: error("invalid deterministic missing entity id")

    private const val CREATE_PREFIX = "create:"
    private const val PAGE_ROWS = 512
    private val CREATABLE_FIELDS = setOf(
        ImportTargetField.ACCOUNT,
        ImportTargetField.CATEGORY,
        ImportTargetField.MERCHANT,
    )
}
