@file:Suppress("MagicNumber", "LongMethod")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.TransactionId
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.DuplicateMatch
import app.ledger.transfer.domain.DuplicateMatchKind
import app.ledger.transfer.domain.DuplicateResolution
import app.ledger.transfer.domain.EncryptedStagingRepository
import app.ledger.transfer.domain.EntityMappingDecision
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportPreparationRequest
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.ImportTransformation
import app.ledger.transfer.domain.StagingAttachment
import app.ledger.transfer.domain.StagingCounts
import app.ledger.transfer.domain.StagingDuplicateCandidate
import app.ledger.transfer.domain.StagingMapping
import app.ledger.transfer.domain.StagingParsedField
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingPreparedCommand
import app.ledger.transfer.domain.StagingRawRow
import app.ledger.transfer.domain.StagingValidationError
import app.ledger.transfer.domain.StagingValue
import app.ledger.transfer.domain.StructuredEntityKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ImportPreparationServiceTest {
    @Test
    fun preparesEveryStructuredEntityKindInDependencyOrder() = runBlocking {
        val rows = StructuredEntityKind.entries.mapIndexed { index, kind -> structuredRow(index + 1L, kind) }
        val staging = MemoryStaging(rows)
        val result = ImportPreparationService().prepare(OPERATION_ID, ImportFormat.STRUCTURED_WORKBOOK, request(), staging).success()

        assertTrue(result.report.canCommit)
        assertEquals(StructuredEntityKind.entries.size.toLong(), result.preparedRows)
        assertEquals(StructuredEntityKind.entries - StructuredEntityKind.TRANSACTION, staging.prepared.mapNotNull { it.structuredKind })
        assertTrue(staging.prepared.single { it.rowNumber == StructuredEntityKind.TRANSACTION.ordinal + 1L }.commandType != null)
        assertTrue(staging.prepared.all { it.payload.bytes.isNotEmpty() })
    }

    @Test
    fun structuredSplitTransactionMustBeExpandedIntoSeparateTransactions() = runBlocking {
        val split = structuredRow(1L, StructuredEntityKind.TRANSACTION).copy(
            fields = structuredRow(1L, StructuredEntityKind.TRANSACTION).fields +
                StagingParsedField("category_count", StagingValue.Integer(2)),
        )
        val staging = MemoryStaging(listOf(split))
        val result = ImportPreparationService().prepare(OPERATION_ID, ImportFormat.STRUCTURED_WORKBOOK, request(), staging).success()

        assertFalse(result.report.canCommit)
        assertEquals(listOf("IMPORT_SEPARATE_TRANSACTIONS_REQUIRED"), result.report.errors.map { it.code })
        assertTrue(staging.prepared.isEmpty())
    }

    @Test
    fun duplicateCandidateBlocksUntilExplicitImportAnywayResolution() = runBlocking {
        val row = structuredRow(1L, StructuredEntityKind.TRANSACTION)
        val matcher = app.ledger.transfer.domain.ExistingTransactionMatcher { _, _ ->
            DomainResult.Success(
                DuplicateMatch(
                    TransactionId(StableId.fromUuid(UUID(0x28L, 701L))),
                    DuplicateMatchKind.CONTENT_HASH,
                    "CONTENT_HASH",
                ),
            )
        }
        val unresolvedStaging = MemoryStaging(listOf(row))
        val unresolved = ImportPreparationService(matcher).prepare(
            OPERATION_ID,
            ImportFormat.STRUCTURED_WORKBOOK,
            request(),
            unresolvedStaging,
        ).success()
        assertFalse(unresolved.report.canCommit)
        assertEquals(listOf("DUPLICATE_REQUIRES_RESOLUTION"), unresolved.report.errors.map { it.code })
        assertEquals(1L, unresolved.duplicateRows)
        assertTrue(unresolvedStaging.prepared.isEmpty())

        val resolvedStaging = MemoryStaging(listOf(row))
        val resolved = ImportPreparationService(matcher).prepare(
            OPERATION_ID,
            ImportFormat.STRUCTURED_WORKBOOK,
            request().copy(duplicateResolutions = mapOf(1L to DuplicateResolution.IMPORT_ANYWAY)),
            resolvedStaging,
        ).success()
        assertTrue(resolved.report.canCommit)
        assertEquals(1L, resolved.preparedRows)
    }

    @Test
    fun unsupportedSpecializedKindFailsDuringPreparationInsteadOfLateCommit() = runBlocking {
        val transfer = structuredRow(1L, StructuredEntityKind.TRANSACTION).copy(
            fields = structuredRow(1L, StructuredEntityKind.TRANSACTION).fields.map {
                if (it.sourceColumn == "kind") it.copy(value = StagingValue.Text("TRANSFER")) else it
            },
        )
        val staging = MemoryStaging(listOf(transfer))
        val result = ImportPreparationService().prepare(
            OPERATION_ID,
            ImportFormat.STRUCTURED_WORKBOOK,
            request(),
            staging,
        ).success()
        assertEquals(listOf("TRANSACTION_KIND_UNSUPPORTED"), result.report.errors.map { it.code })
        assertTrue(staging.prepared.isEmpty())
    }

    @Test
    fun missingEntitySummaryCountsUniqueEntitiesAndAllowsBlankOptionalMerchant() = runBlocking {
        val account = "现金"
        val category = "餐饮"
        val mappings = listOf(
            StagingMapping("kind", ImportTargetField.TRANSACTION_KIND, ImportTransformation.Identity),
            StagingMapping("category", ImportTargetField.CATEGORY, ImportTransformation.Identity),
            StagingMapping("amount", ImportTargetField.AMOUNT_EXPRESSION, ImportTransformation.Identity),
            StagingMapping("currency", ImportTargetField.CURRENCY, ImportTransformation.Identity),
            StagingMapping("account", ImportTargetField.ACCOUNT, ImportTransformation.Identity),
            StagingMapping("occurred_at", ImportTargetField.OCCURRED_AT, ImportTransformation.Identity),
            StagingMapping("merchant", ImportTargetField.MERCHANT, ImportTransformation.Identity),
        )
        val decisions = listOf(
            EntityMappingDecision(ImportTargetField.ACCOUNT, account, null, true),
            EntityMappingDecision(ImportTargetField.CATEGORY, category, null, true),
        )
        val rows = (1L..2L).map { number ->
            StagingParsedRow(
                number,
                mapOf(
                    "_sheet" to "transactions",
                    "kind" to "EXPENSE",
                    "category" to category,
                    "amount" to "100",
                    "currency" to "JPY",
                    "account" to account,
                    "occurred_at" to "2026-08-09T00:00:00Z",
                    "merchant" to "",
                ).map { (name, value) -> StagingParsedField(name, StagingValue.Text(value)) },
            )
        }
        val result = ImportPreparationService().prepare(
            OPERATION_ID,
            ImportFormat.XLSX,
            ImportPreparationRequest("JPY", mappings, decisions, emptyList(), emptyMap(), setOf("transactions")),
            MemoryStaging(rows),
        ).success()
        assertTrue(result.report.canCommit)
        assertEquals(2L, result.preparedRows)
        assertEquals(2L, result.missingEntitiesToCreate)
    }

    private fun structuredRow(number: Long, kind: StructuredEntityKind): StagingParsedRow {
        val id = UUID(0x28L, number).toString()
        val fields = mutableMapOf(
            "_sheet" to kind.canonicalSheetName,
            "id" to id,
            "name" to "entity-$number",
            "currency" to "JPY",
            "type" to "CASH",
            "account_id" to UUID(0x28L, 100L).toString(),
            "ledger_account_id" to UUID(0x28L, 104L).toString(),
            "direction" to "EXPENSE",
            "latitude_e7" to "356800000",
            "longitude_e7" to "1397600000",
            "start_date" to "2026-08-09",
            "kind" to "EXPENSE",
            "category" to UUID(0x28L, 101L).toString(),
            "amount" to "100",
            "account" to UUID(0x28L, 100L).toString(),
            "occurred_at" to "2026-08-09T00:00:00Z",
            "cycle_start" to "2026-08-01",
            "cycle_end" to "2026-08-31",
            "due_date" to "2026-09-10",
            "purchase_transaction_id" to UUID(0x28L, 102L).toString(),
            "credit_account_id" to UUID(0x28L, 100L).toString(),
            "original_principal_minor" to "1200",
            "term_count" to "12",
            "first_statement_date" to "2026-09-25",
            "principal" to "1200",
            "end_date" to "2027-08-09",
            "payment_count" to "12",
            "first_payment_date" to "2026-08-31",
            "annual_rate" to "0.03",
            "month" to "2026-08",
            "amount" to "100",
            "target_amount" to "5000",
            "transaction_kind" to "EXPENSE",
            "frequency" to "MONTHLY_DAY",
            "start_at" to "2026-08-09",
            "activity_id" to UUID(0x28L, 103L).toString(),
            "participant_ids" to "${UUID(0x28L, 105L)}|${UUID(0x28L, 106L)}",
            "participant_names" to "Me|Friend",
            "self_participant_id" to UUID(0x28L, 105L).toString(),
            "captured_at" to "2026-08-09T00:00:00Z",
        )
        return StagingParsedRow(number, fields.map { (name, value) -> StagingParsedField(name, StagingValue.Text(value)) })
    }

    private fun request() = ImportPreparationRequest("JPY", emptyList(), emptyList(), emptyList(), emptyMap())

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val OPERATION_ID = BackgroundOperationId(StableId.fromUuid(UUID(0x28L, 999L)))
    }
}

private class MemoryStaging(private val rows: List<StagingParsedRow>) : EncryptedStagingRepository {
    val prepared = mutableListOf<StagingPreparedCommand>()
    override suspend fun create(operationId: BackgroundOperationId) = DomainResult.Success(Unit)
    override suspend fun appendRaw(rows: List<StagingRawRow>) = DomainResult.Success(Unit)
    override suspend fun appendParsed(rows: List<StagingParsedRow>) = DomainResult.Success(Unit)
    override suspend fun saveMappings(mappings: List<StagingMapping>) = DomainResult.Success(Unit)
    override suspend fun saveErrors(errors: List<StagingValidationError>) = DomainResult.Success(Unit)
    override suspend fun saveDuplicates(candidates: List<StagingDuplicateCandidate>) = DomainResult.Success(Unit)
    override suspend fun savePrepared(commands: List<StagingPreparedCommand>): DomainResult<Unit> {
        prepared += commands
        return DomainResult.Success(Unit)
    }
    override suspend fun saveAttachments(attachments: List<StagingAttachment>) = DomainResult.Success(Unit)
    override suspend fun rawRows(offsetExclusive: Long, limit: Int) = DomainResult.Success(emptyList<StagingRawRow>())
    override suspend fun parsedRows(offsetExclusive: Long, limit: Int) = DomainResult.Success(rows.filter { it.rowNumber > offsetExclusive }.take(limit))
    override suspend fun preparedCommands(offsetExclusive: Long, limit: Int) = DomainResult.Success(prepared.filter { it.rowNumber > offsetExclusive }.take(limit))
    override suspend fun counts() = DomainResult.Success(StagingCounts(0, rows.size.toLong(), 0, 0, prepared.size.toLong()))
    override suspend fun destroy() = DomainResult.Success(Unit)
}
