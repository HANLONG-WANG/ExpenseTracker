package app.ledger.app

import app.ledger.feature.transfer.ExportExecutionPresentation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExportOperationCenterMappingTest {
    @Test
    fun `export durable states map to operation center without exposing operation payload`() {
        exportOperationCenterPresentation("EXP-001", ExportExecutionPresentation.RUNNING) shouldBe OperationCenterPresentation.EMPTY
        exportOperationCenterPresentation("EXP-004", ExportExecutionPresentation.RUNNING) shouldBe OperationCenterPresentation.ACTIVE
        exportOperationCenterPresentation("EXP-004", ExportExecutionPresentation.CANCEL_REQUESTED) shouldBe OperationCenterPresentation.ACTIVE
        exportOperationCenterPresentation("EXP-004", ExportExecutionPresentation.FAILED) shouldBe OperationCenterPresentation.FAILED
        exportOperationCenterPresentation("EXP-004", ExportExecutionPresentation.SUCCEEDED) shouldBe OperationCenterPresentation.COMPLETED
    }
}
