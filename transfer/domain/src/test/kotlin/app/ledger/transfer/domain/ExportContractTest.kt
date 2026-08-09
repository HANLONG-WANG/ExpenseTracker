package app.ledger.transfer.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test

class ExportContractTest {
    @Test
    fun ordinaryExportFieldSetIsClosedAndCoordinatesAreExplicitOptIn() {
        ExportField.locationCoordinates.isNotEmpty().shouldBeTrue()
        ExportField.defaultSelection.intersect(ExportField.locationCoordinates).isEmpty().shouldBeTrue()
        ExportField.defaultSelection.isNotEmpty().shouldBeTrue()
        ExportField.entries.any { it.header.contains("vault", true) || it.header.contains("pan", true) || it.header.contains("cvc", true) }
            .shouldBeFalse()
    }

    @Test
    fun contentAndFormatCombinationsCannotMasqueradeAsBackup() {
        shouldThrow<IllegalArgumentException> {
            ExportDescriptor(ExportContent.CURRENT_FILTER, ExportFormat.XLSX, "bad.xlsx")
        }
        shouldThrow<IllegalArgumentException> {
            ExportDescriptor(ExportContent.FULL_WORKBOOK, ExportFormat.CSV, "bad.csv")
        }
        shouldThrow<IllegalArgumentException> {
            ExportDescriptor(ExportContent.CURRENT_FILTER, ExportFormat.PORTABLE_BACKUP, "bad.bin")
        }
    }

    @Test
    fun locationFlagAndSelectedFieldsCannotDiverge() {
        shouldThrow<IllegalArgumentException> {
            ExportDescriptor(
                ExportContent.CURRENT_FILTER,
                ExportFormat.CSV,
                "bad.csv",
                fields = ExportField.defaultSelection + ExportField.LATITUDE_E7,
                includeLocationCoordinates = true,
            )
        }
    }
}
