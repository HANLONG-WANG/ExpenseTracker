package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.ui.features.receiptCurrencyText
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class ReceiptArchivesTest {
    private val examples = File("../../docs/formats/examples")
    @Test fun displayedFileMoneyPreservesFractionalUnitPricesAndCurrencyPrecision() {
        assertEquals("¥1,200",receiptCurrencyText("1200","JPY"))
        assertEquals("¥0.5",receiptCurrencyText("0.5","JPY"))
        assertEquals("CN¥12.50",receiptCurrencyText("12.5","CNY"))
        assertEquals("−US$0.50",receiptCurrencyText("-0.50","USD"))
    }
    @Test fun realOriginalBytesRoundTripWithManifestAndReceiptBoundaryParts() {
        val directory = Files.createTempDirectory("receipt-archive-test").toFile()
        try {
            val template = FileProtocols.receiptBundle(File(examples, "receipt-bundle.valid.json").readText())
            val original = File(directory, "original").apply { writeBytes(ByteArray(180000) { (it % 251).toByte() }) }
            val digest = MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) }
            fun source(): ReceiptArchiveSource {
                val rid = UUID.randomUUID().toString()
                val images = (0..1).map { position ->
                    val id = UUID.randomUUID().toString()
                    template.records.single().images.single().copy(image_id = id, position = position, path = "images/$rid/$id", sha256 = digest, size_bytes = original.length())
                }
                return ReceiptArchiveSource(template.records.single().copy(receipt_id = rid, record_id = UUID.randomUUID().toString(), images = images), images.associate { it.image_id to original })
            }
            val sources = listOf(source(), source())
            val archives = writeReceiptArchives(template.bundle_id, template.book_id, sources, File(directory, "zip"), 400000)
            assertEquals(2, archives.size)
            archives.forEachIndexed { index, archive -> ZipFile(archive).use { zip ->
                val manifest = FileProtocols.receiptBundle(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                assertEquals(index + 1, manifest.part); assertEquals(2, manifest.parts)
                assertEquals(template.bundle_id, manifest.bundle_id)
                assertEquals(3, zip.size())
                manifest.records.single().images.forEach { image ->
                    assertArrayEquals(original.readBytes(), zip.getInputStream(zip.getEntry(image.path)).readBytes())
                }
            } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun changedOriginalFailsAndRemovesPartialArchives() {
        val directory = Files.createTempDirectory("receipt-invalid-test").toFile()
        try {
            val template = FileProtocols.receiptBundle(File(examples, "receipt-bundle.valid.json").readText())
            val original = File(directory, "changed").apply { writeText("changed original bytes") }
            val source = ReceiptArchiveSource(template.records.single(), mapOf(template.records.single().images.single().image_id to original))
            val output = File(directory, "zip")
            assertThrows(IllegalStateException::class.java) { writeReceiptArchives(template.bundle_id, template.book_id, listOf(source), output) }
            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun receiptTotalsExcludeIncludedTaxAndDoNotInferMissingLineAmounts() {
        val record = FileProtocols.receiptItems(File(examples, "receipt-items.valid.json").readText()).records.single()
        val adjustments = listOf(ReceiptAdjustment("tax", "120", true), ReceiptAdjustment("discount", "100", false), ReceiptAdjustment("shipping", "50", false))
        val totals = receiptTotals(record.copy(adjustments = adjustments), FileMoney("JPY", "1200"))
        assertEquals("1150", totals.knownTotal.toPlainString())
        assertEquals("-50", totals.differenceFromOriginal!!.toPlainString())
        val unknown = receiptTotals(record.copy(items = record.items.map { it.copy(line_amount = null) }), FileMoney("JPY", "1200"))
        assertFalse(unknown.complete)
        assertEquals(0, unknown.knownItems.signum())
        assertNull(receiptTotals(record, FileMoney("CNY", "12.00")).differenceFromOriginal)
    }
}
