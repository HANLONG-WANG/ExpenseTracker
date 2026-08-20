@file:Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount", "TooManyFunctions")

package app.ledger.transfer.data

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.DuplicateMatch
import app.ledger.transfer.domain.DuplicateResolution
import app.ledger.transfer.domain.EncryptedStagingRepository
import app.ledger.transfer.domain.EntityMappingDecision
import app.ledger.transfer.domain.ExistingTransactionMatcher
import app.ledger.transfer.domain.FxImportDecision
import app.ledger.transfer.domain.ImportBoundaryPolicy
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportPreparationRequest
import app.ledger.transfer.domain.ImportPreparationResult
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.ImportTransformation
import app.ledger.transfer.domain.ImportValidationIssue
import app.ledger.transfer.domain.ImportValidationReport
import app.ledger.transfer.domain.ImportValidationSeverity
import app.ledger.transfer.domain.PreparedCommandPayload
import app.ledger.transfer.domain.PreparedCommandValidationState
import app.ledger.transfer.domain.StagingDuplicateCandidate
import app.ledger.transfer.domain.StagingMapping
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingPreparedCommand
import app.ledger.transfer.domain.StagingValidationError
import app.ledger.transfer.domain.StagingValue
import app.ledger.transfer.domain.StructuredEntityKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ImportPreparationService(
    private val duplicateMatcher: ExistingTransactionMatcher = ExistingTransactionMatcher { _, _ -> DomainResult.Success(null) },
) {
    private val currencies = JvmLegalTenderCurrencyCatalog.create()
    suspend fun prepare(
        operationId: BackgroundOperationId,
        format: ImportFormat,
        request: ImportPreparationRequest,
        staging: EncryptedStagingRepository,
    ): DomainResult<ImportPreparationResult> {
        val mappings = persistedMappings(request)
        staging.saveMappings(mappings).failureOrNull()?.let { return it }
        val returnedIssues = mutableListOf<ImportValidationIssue>()
        var totalErrors = 0L
        var totalWarnings = 0L
        var preparedRows = 0L
        var duplicateRows = 0L
        val missingCreates = linkedSetOf<Pair<ImportTargetField, String>>()
        var offset = 0L
        while (true) {
            val page = when (val loaded = staging.parsedRows(offset, PAGE_SIZE)) {
                is DomainResult.Success -> loaded.value
                is DomainResult.Failure -> return loaded
            }
            if (page.isEmpty()) break
            val pageErrors = mutableListOf<StagingValidationError>()
            val pageDuplicates = mutableListOf<StagingDuplicateCandidate>()
            val pagePrepared = mutableListOf<StagingPreparedCommand>()
            for (row in page) {
                val outcome = prepareRow(operationId, format, row, request)
                outcome.issues.forEach { issue ->
                    if (issue.severity == ImportValidationSeverity.ERROR) totalErrors++ else totalWarnings++
                    if (returnedIssues.size < MAX_RETURNED_ISSUES) returnedIssues += issue
                    pageErrors += StagingValidationError(row.rowNumber, issue.field, issue.code)
                }
                missingCreates += outcome.missingCreates
                val prepared = outcome.prepared ?: continue
                val duplicate = when (val found = duplicateMatcher.find(row.rowNumber, prepared.payloadHash)) {
                    is DomainResult.Success -> found.value
                    is DomainResult.Failure -> return found
                }
                if (duplicate != null) {
                    duplicateRows++
                    pageDuplicates += duplicate.toCandidate(row.rowNumber)
                    when (request.duplicateResolutions[row.rowNumber]) {
                        DuplicateResolution.SKIP -> pagePrepared += prepared.copy(
                            validationState = PreparedCommandValidationState.DUPLICATE_SKIPPED,
                        )
                        DuplicateResolution.IMPORT_ANYWAY -> pagePrepared += prepared
                        null -> {
                            totalErrors++
                            val issue = ImportValidationIssue(
                                row.rowNumber,
                                null,
                                "DUPLICATE_REQUIRES_RESOLUTION",
                                ImportValidationSeverity.ERROR,
                            )
                            if (returnedIssues.size < MAX_RETURNED_ISSUES) returnedIssues += issue
                            pageErrors += StagingValidationError(row.rowNumber, null, issue.code)
                        }
                    }
                } else {
                    pagePrepared += prepared
                }
            }
            staging.saveErrors(pageErrors).failureOrNull()?.let { return it }
            staging.saveDuplicates(pageDuplicates).failureOrNull()?.let { return it }
            staging.savePrepared(pagePrepared).failureOrNull()?.let { return it }
            preparedRows += pagePrepared.count { it.validationState == PreparedCommandValidationState.DOMAIN_VALIDATED }
            offset = page.last().rowNumber
        }
        return DomainResult.Success(
            ImportPreparationResult(
                ImportValidationReport(returnedIssues, totalErrors, totalWarnings),
                preparedRows,
                duplicateRows,
                missingCreates.size.toLong(),
            ),
        )
    }

    private fun prepareRow(
        operationId: BackgroundOperationId,
        format: ImportFormat,
        row: StagingParsedRow,
        request: ImportPreparationRequest,
    ): RowOutcome {
        val raw = row.fields.associate { it.sourceColumn to it.value.canonical() }
        val sheet = raw.getValue("_sheet")
        if (request.includedSheets.isNotEmpty() && sheet !in request.includedSheets) return RowOutcome(emptyList())
        val structuredKind = if (format == ImportFormat.STRUCTURED_WORKBOOK) StructuredEntityKind.fromSheetName(sheet) else null
        if (format == ImportFormat.STRUCTURED_WORKBOOK && structuredKind == null) {
            return RowOutcome(listOf(error(row.rowNumber, null, ImportFailure.UnsupportedSource.code)))
        }
        if (structuredKind != null && structuredKind != StructuredEntityKind.TRANSACTION) {
            val missing = StructuredWorkbookSchema.missingColumns(structuredKind, raw.keys)
            if (missing.isNotEmpty()) {
                return RowOutcome(missing.map { error(row.rowNumber, null, "STRUCTURED_REQUIRED_${it.uppercase()}") })
            }
            val payload = PreparedPayloadCodec.encodeStructured(structuredKind, raw)
            return RowOutcome(
                issues = emptyList(),
                prepared = staged(operationId, row.rowNumber, null, structuredKind, payload),
            )
        }

        val transactionMappings = if (structuredKind == StructuredEntityKind.TRANSACTION) {
            STRUCTURED_TRANSACTION_MAPPINGS
        } else {
            request.mappings
        }
        val mappingByTarget = transactionMappings.groupBy(StagingMapping::targetField)
        val missingMappings = REQUIRED_TRANSACTION_FIELDS.filter { mappingByTarget[it].isNullOrEmpty() }
        if (missingMappings.isNotEmpty()) {
            return RowOutcome(missingMappings.map { error(row.rowNumber, it, ImportFailure.MissingRequiredMapping.code) })
        }
        val mapped = mutableMapOf<ImportTargetField, String>()
        val issues = mutableListOf<ImportValidationIssue>()
        transactionMappings.forEach { mapping ->
            val value = raw[mapping.sourceColumn] ?: return@forEach
            val transformed = runCatching { transform(value, mapping.transformation) }.getOrNull()
            if (transformed == null) {
                issues += error(row.rowNumber, mapping.targetField, "FIELD_TRANSFORMATION_INVALID")
            } else if (mapped.put(mapping.targetField, transformed) != null) {
                issues += error(row.rowNumber, mapping.targetField, "MULTIPLE_SOURCE_COLUMNS_FOR_SINGLE_FIELD")
            }
        }
        val minorUnitMapping = transactionMappings.singleOrNull {
            it.targetField == ImportTargetField.AMOUNT_EXPRESSION && it.sourceColumn.equals("amount_minor", ignoreCase = true)
        }
        if (minorUnitMapping != null) {
            val currency = mapped[ImportTargetField.CURRENCY]
                ?.let { CurrencyCode.parse(it).getOrNull() }
            val fractionDigits = currency?.let { currencies.find(it)?.fractionDigits }
            val minor = mapped[ImportTargetField.AMOUNT_EXPRESSION]?.toBigDecimalOrNull()
            if (fractionDigits == null || minor == null) {
                issues += error(row.rowNumber, ImportTargetField.AMOUNT_EXPRESSION, "MINOR_UNIT_AMOUNT_INVALID")
            } else {
                mapped[ImportTargetField.AMOUNT_EXPRESSION] = minor.movePointLeft(fractionDigits).stripTrailingZeros().toPlainString()
            }
        }
        val categoryCount = raw["category_count"]?.toIntOrNull() ?: mapped[ImportTargetField.CATEGORY]?.split('|')?.size ?: 0
        val payerCount = raw["payer_count"]?.toIntOrNull() ?: mapped[ImportTargetField.PAYER]?.split('|')?.size ?: 0
        issues += ImportBoundaryPolicy.validateSingleTransaction(row.rowNumber, categoryCount, payerCount).issues

        val missingCreates = linkedSetOf<Pair<ImportTargetField, String>>()
        request.entityDecisions.forEach { decision ->
            if (mapped[decision.targetField] == decision.sourceValue) {
                mapped[decision.targetField] = decision.existingEntityId?.toString() ?: "create:${decision.sourceValue}"
                if (decision.createMissing) missingCreates += decision.targetField to decision.sourceValue
            }
        }
        ENTITY_FIELDS.forEach { field ->
            val value = mapped[field] ?: return@forEach
            if (value.isBlank()) {
                if (field in REQUIRED_ENTITY_FIELDS) {
                    issues += error(row.rowNumber, field, "ENTITY_MAPPING_REQUIRED")
                } else {
                    mapped.remove(field)
                }
                return@forEach
            }
            if (!value.startsWith("create:") && runCatching { java.util.UUID.fromString(value) }.isFailure) {
                issues += error(row.rowNumber, field, "ENTITY_MAPPING_REQUIRED")
            }
        }
        val currency = mapped[ImportTargetField.CURRENCY]
        if (currency != null && currency != request.baseCurrency) {
            val fx = request.fxDecisions.singleOrNull { it.sourceCurrency == currency && it.targetCurrency == request.baseCurrency }
            val rate = fx?.rate
            if (rate == null) {
                issues += error(row.rowNumber, ImportTargetField.FX_RATE, "FX_MANUAL_RATE_REQUIRED")
            } else {
                mapped[ImportTargetField.FX_RATE] = rate.toPlainString()
            }
        }
        val commandType = commandType(mapped[ImportTargetField.TRANSACTION_KIND])
        if (commandType == null) issues += error(row.rowNumber, ImportTargetField.TRANSACTION_KIND, "TRANSACTION_KIND_UNSUPPORTED")
        if (issues.any { it.severity == ImportValidationSeverity.ERROR } || commandType == null) {
            return RowOutcome(issues, missingCreates = missingCreates)
        }
        val payload = PreparedPayloadCodec.encodeTransaction(mapped)
        return RowOutcome(issues, staged(operationId, row.rowNumber, commandType, null, payload), missingCreates)
    }

    private fun persistedMappings(request: ImportPreparationRequest): List<StagingMapping> {
        val entityByField = request.entityDecisions.groupBy(EntityMappingDecision::targetField)
        val enriched = request.mappings.map { mapping ->
            val entries = entityByField[mapping.targetField].orEmpty().associate { decision ->
                decision.sourceValue to (decision.existingEntityId?.toString() ?: "create:${decision.sourceValue}")
            }
            if (entries.isEmpty()) mapping else mapping.copy(transformation = ImportTransformation.ClosedValueMap(entries))
        }
        val fx = request.fxDecisions.map { decision ->
            StagingMapping(
                "__fx__:${decision.sourceCurrency}:${decision.targetCurrency}",
                ImportTargetField.FX_RATE,
                ImportTransformation.ClosedValueMap(
                    mapOf(decision.sourceCurrency to (decision.rate?.toPlainString() ?: "manual")),
                ),
            )
        }
        return enriched + fx
    }

    private fun staged(
        operationId: BackgroundOperationId,
        rowNumber: Long,
        commandType: FinancialCommandType?,
        structuredKind: StructuredEntityKind?,
        payload: ByteArray,
    ): StagingPreparedCommand {
        val idBytes = MessageDigest.getInstance("SHA-256").digest(
            operationId.value.bytes + rowNumber.toString().toByteArray(Charsets.US_ASCII) + payload,
        ).copyOf(StableId.BYTE_COUNT)
        return StagingPreparedCommand(
            rowNumber,
            CommandId(StableId.fromBytes(idBytes).requireValue()),
            commandType,
            structuredKind,
            PreparedCommandPayload.of(payload).requireValue(),
            Hash256.sha256(payload),
            PreparedCommandValidationState.DOMAIN_VALIDATED,
        )
    }

    private fun transform(value: String, transformation: ImportTransformation): String = when (transformation) {
        ImportTransformation.Identity -> value
        is ImportTransformation.DatePattern -> LocalDate.parse(value, DateTimeFormatter.ofPattern(transformation.pattern)).toString()
        is ImportTransformation.DecimalSeparator -> value.replace(transformation.separator, '.').toBigDecimal().toPlainString()
        is ImportTransformation.ClosedValueMap -> transformation.entries[value] ?: error("closed value not mapped")
    }

    private fun commandType(value: String?): FinancialCommandType? = when (value?.trim()?.uppercase()) {
        "EXPENSE", "支出" -> FinancialCommandType.RECORD_EXPENSE
        "INCOME", "收入" -> FinancialCommandType.RECORD_INCOME
        else -> null
    }

    private fun DuplicateMatch.toCandidate(rowNumber: Long) = StagingDuplicateCandidate(
        rowNumber,
        transactionId,
        kind,
        confidenceCode,
    )

    private fun error(row: Long, field: ImportTargetField?, code: String) = ImportValidationIssue(
        row,
        field,
        code,
        ImportValidationSeverity.ERROR,
    )

    private data class RowOutcome(
        val issues: List<ImportValidationIssue>,
        val prepared: StagingPreparedCommand? = null,
        val missingCreates: Set<Pair<ImportTargetField, String>> = emptySet(),
    )

    private companion object {
        const val PAGE_SIZE = 512
        const val MAX_RETURNED_ISSUES = 1_000
        val REQUIRED_TRANSACTION_FIELDS = setOf(
            ImportTargetField.TRANSACTION_KIND,
            ImportTargetField.CATEGORY,
            ImportTargetField.AMOUNT_EXPRESSION,
            ImportTargetField.CURRENCY,
            ImportTargetField.ACCOUNT,
            ImportTargetField.OCCURRED_AT,
        )
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
        val REQUIRED_ENTITY_FIELDS = setOf(ImportTargetField.CATEGORY, ImportTargetField.ACCOUNT)
        val STRUCTURED_TRANSACTION_MAPPINGS = listOf(
            StagingMapping("kind", ImportTargetField.TRANSACTION_KIND, ImportTransformation.Identity),
            StagingMapping("category", ImportTargetField.CATEGORY, ImportTransformation.Identity),
            StagingMapping("amount", ImportTargetField.AMOUNT_EXPRESSION, ImportTransformation.Identity),
            StagingMapping("currency", ImportTargetField.CURRENCY, ImportTransformation.Identity),
            StagingMapping("account", ImportTargetField.ACCOUNT, ImportTransformation.Identity),
            StagingMapping("occurred_at", ImportTargetField.OCCURRED_AT, ImportTransformation.Identity),
            StagingMapping("card", ImportTargetField.CARD, ImportTransformation.Identity),
            StagingMapping("merchant", ImportTargetField.MERCHANT, ImportTransformation.Identity),
            StagingMapping("project", ImportTargetField.PROJECT, ImportTransformation.Identity),
            StagingMapping("location", ImportTargetField.LOCATION, ImportTransformation.Identity),
            StagingMapping("payer", ImportTargetField.PAYER, ImportTransformation.Identity),
            StagingMapping("payee", ImportTargetField.PAYEE, ImportTransformation.Identity),
            StagingMapping("note", ImportTargetField.NOTE, ImportTransformation.Identity),
        )
    }
}

private object StructuredWorkbookSchema {
    private val required = mapOf(
        StructuredEntityKind.ACCOUNT to setOf("id", "name", "currency", "type"),
        StructuredEntityKind.CARD to setOf("id", "account_id", "name", "type"),
        StructuredEntityKind.CATEGORY to setOf("id", "name", "direction"),
        StructuredEntityKind.MERCHANT to setOf("id", "name"),
        StructuredEntityKind.PLACE to setOf("id", "name", "latitude_e7", "longitude_e7"),
        StructuredEntityKind.PROJECT to setOf("id", "name", "start_date"),
        StructuredEntityKind.SETTLEMENT_ACTIVITY to setOf(
            "id",
            "name",
            "currency",
            "start_date",
            "participant_ids",
            "participant_names",
            "self_participant_id",
        ),
        StructuredEntityKind.TRANSACTION to setOf("kind", "category", "amount", "currency", "account", "occurred_at"),
        StructuredEntityKind.CREDIT_STATEMENT to setOf("id", "account_id", "cycle_start", "cycle_end", "due_date"),
        StructuredEntityKind.INSTALLMENT to setOf(
            "id",
            "purchase_transaction_id",
            "credit_account_id",
            "currency",
            "original_principal_minor",
            "term_count",
            "first_statement_date",
        ),
        StructuredEntityKind.LOAN to setOf(
            "id", "account_id", "ledger_account_id", "name", "principal", "currency", "start_date", "end_date",
            "payment_count", "first_payment_date", "annual_rate",
        ),
        StructuredEntityKind.BUDGET to setOf("id", "month", "amount"),
        StructuredEntityKind.GOAL to setOf("id", "account_id", "name", "target_amount"),
        StructuredEntityKind.RECURRENCE to setOf("id", "name", "transaction_kind", "frequency", "start_at"),
        StructuredEntityKind.LOCATION to setOf("id", "latitude_e7", "longitude_e7", "captured_at"),
    )

    fun missingColumns(kind: StructuredEntityKind, columns: Set<String>): Set<String> = required.getValue(kind) - columns
}

private object PreparedPayloadCodec {
    fun encodeTransaction(values: Map<ImportTargetField, String>): ByteArray = encode(
        "transaction",
        values.mapKeys { it.key.name },
    )

    fun encodeStructured(kind: StructuredEntityKind, values: Map<String, String>): ByteArray = encode(
        kind.canonicalSheetName,
        values.filterKeys { !it.startsWith('_') },
    )

    private fun encode(type: String, values: Map<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeSizedUtf8(type)
            output.writeInt(values.size)
            values.toSortedMap().forEach { (key, value) ->
                output.writeSizedUtf8(key)
                output.writeSizedUtf8(value)
            }
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeSizedUtf8(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_FIELD_BYTES)
        writeInt(encoded.size)
        write(encoded)
    }

    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024
}

private fun StagingValue.canonical(): String = when (this) {
    is StagingValue.Text -> value
    is StagingValue.Integer -> value.toString()
    is StagingValue.Decimal -> value.toPlainString()
    is StagingValue.Date -> value.toString()
    is StagingValue.InstantValue -> value.toString()
    StagingValue.Empty -> ""
}

private fun DomainResult<*>.failureOrNull(): DomainResult.Failure? = this as? DomainResult.Failure
