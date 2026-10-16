package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.domain.reconcile.*
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class FileProtocolsTest {
    private fun sample(name: String) = File("../../docs/formats/examples/$name.json").readText()
    @Test fun formalExamplesDecodeAndKeepUnknownOptionalFieldsCompatible() {
        val statement = FileProtocols.statement(sample("statement.valid").replace("\"version\": 1", "\"version\": 1, \"future_optional\": {\"value\": true}"))
        assertEquals(2, statement.rows.size)
        assertEquals(listOf("$.future_optional"),unknownFileFields(sample("statement.valid").replace("\"version\": 1", "\"version\": 1, \"future_optional\": true"),StatementFile.serializer().descriptor))
        val bundle = FileProtocols.receiptBundle(sample("receipt-bundle.valid"))
        val items = FileProtocols.receiptItems(sample("receipt-items.valid"))
        assertEquals(bundle.bundle_id, items.bundle_id)
        assertEquals(bundle.records.single().images.single().image_id, items.records.single().image_ids.single())
    }
    @Test fun invalidStatementReportsSemanticPathsTogetherAndRejectsWrongVersion() {
        val error = assertThrows(FileValidationException::class.java) { FileProtocols.statement(sample("statement.invalid")) }
        assertTrue(error.problems.any { it.path == "$.rows[0].settlement.amount" })
        assertTrue(error.problems.any { it.path == "$.rows.row_id" })
        assertTrue(error.problems.any { it.path == "$.rows[1].date" })
        val version = assertThrows(FileValidationException::class.java) { FileProtocols.statement(sample("statement.valid").replace("\"version\": 1", "\"version\": 2")) }
        assertEquals("$.version", version.problems.single().path)
    }
    @Test fun receiptImageReferencesAndUnknownAmountsAreNotInvented() {
        val error = assertThrows(FileValidationException::class.java) { FileProtocols.receiptItems(sample("receipt-items.invalid")) }
        assertTrue(error.problems.any { it.path == "$.records[0].items[0].image_ids" })
        val unknown = FileProtocols.receiptItems(sample("receipt-items.valid").replace("\"line_amount\": \"1200\"", "\"line_amount\": null"))
        assertNull(unknown.records.single().items.single().line_amount)
    }
    @Test fun monetaryPrecisionRangeAndDateBasisAreStrict() {
        val source = sample("statement.valid")
        listOf(source.replace("\"1200\"", "\"9223372036854775808\""),
            source.replace("\"1200\"", "1200"), source.replace("\"2026-10-05\"", "\"2026-02-30\""),
            source.replace("\"posted_date\": \"2026-10-05\"", "\"posted_date\": \"2026-10-06\"")
        ).forEach { invalid -> assertThrows(FileValidationException::class.java) { FileProtocols.statement(invalid) } }
    }
    private fun posting(file: StatementFile, id: String, amount: String = "1200", date: String = "2026-10-05") =
        ReconcilePosting(file.book_id, file.account_id, id, "event-$id", "JPY", BigDecimal(amount), "out", date, "2026-10-04")
    @Test fun sameDaySameAmountUsesEachPostingOnceAndRespectsOtherBatches() {
        val file = FileProtocols.statement(sample("statement.valid"))
        val postings = listOf(posting(file, "a"), posting(file, "b"), posting(file, "c"))
        val result = matchStatement(file, postings, setOf("a"))
        assertEquals(listOf("b", "c"), result.exact.map { it.postingId })
        assertTrue(result.ledgerOnly.isEmpty())
        val one = matchStatement(file, postings.take(1))
        assertEquals(1, one.exact.size)
        assertEquals(1, one.statementOnly.size)
    }
    @Test fun exactMatchesTakePriorityAndDifferencesNeverBecomeExact() {
        val file = FileProtocols.statement(sample("statement.valid"))
        val rows = file.rows.mapIndexed { i, row -> if (i == 0) row.copy(date = "2026-10-06", posted_date = "2026-10-06") else row }
        val result = matchStatement(file.copy(rows = rows), listOf(posting(file, "a"), posting(file, "b", "1300", "2026-10-06")))
        assertEquals(file.rows[1].row_id, result.exact.single().rowId)
        assertEquals(listOf("b"), result.differences.single().candidatePostingIds)
    }
    @Test fun coverageRequiresCompleteCleanFileAndNeverLeaksToTransferOtherSide() {
        val file = FileProtocols.statement(sample("statement.valid"))
        val local = posting(file, "local", "500")
        val other = local.copy(accountId = "other-account", postingId = "other")
        val outside = local.copy(postingId = "outside", postedDate = "2026-11-01")
        val result = matchStatement(file, listOf(local, other, outside))
        assertEquals(listOf("local"), result.ledgerOnly)
        assertEquals(listOf("outside"), result.uncovered)
        for (partial in listOf(file.copy(complete = false), file.copy(issues = listOf("缺页")))) {
            val r = matchStatement(partial, listOf(local, other))
            assertTrue(r.ledgerOnly.isEmpty())
            assertEquals(listOf("local"), r.uncovered)
        }
        val wrongDate = matchStatement(file.copy(date_basis = "occurred_date"), listOf(posting(file, "a")))
        assertTrue(wrongDate.exact.isEmpty())
        assertTrue(wrongDate.differences.isNotEmpty())
    }
}
