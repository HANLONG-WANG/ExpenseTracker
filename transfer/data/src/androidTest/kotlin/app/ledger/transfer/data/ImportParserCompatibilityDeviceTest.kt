@file:Suppress("MagicNumber")

package app.ledger.transfer.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.ImportInput
import app.ledger.transfer.domain.ImportReadRequest
import kotlinx.coroutines.runBlocking
import org.dhatim.fastexcel.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ImportParserCompatibilityDeviceTest {
    @Test
    fun commonsCsvIcuAndFastExcelOperateOnAndroidRuntime() = runBlocking {
        assertTrue(Build.VERSION.SDK_INT in 28..36)
        val csv = "note,amount\n東京,42\n".toByteArray(Charsets.UTF_8)
        var csvValue = ""
        val csvSummary = AndroidCsvImportReader().read(request(csv)) { row ->
            csvValue = requireNotNull(row.cells.first().canonicalValue)
        }.success()
        assertEquals("東京", csvValue)
        assertEquals(1L, csvSummary.rowCount)

        val xlsx = ByteArrayOutputStream().use { output ->
            Workbook(output, "ledger-device-test", "01.2026").use { workbook ->
                workbook.newWorksheet("transactions").use { sheet ->
                    sheet.value(0, 0, "note")
                    sheet.value(1, 0, "東京")
                }
            }
            output.toByteArray()
        }
        var xlsxValue = ""
        val xlsxSummary = FastExcelImportReader().read(request(xlsx)) { row ->
            xlsxValue = requireNotNull(row.cells.first().canonicalValue)
        }.success()
        assertEquals("東京", xlsxValue)
        assertEquals(1L, xlsxSummary.rowCount)
        assertEquals(1, xlsxSummary.peakBufferedRows)
    }

    private fun request(bytes: ByteArray): ImportReadRequest = ImportReadRequest(
        ImportInput { ByteArrayInputStream(bytes) },
    )

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}
