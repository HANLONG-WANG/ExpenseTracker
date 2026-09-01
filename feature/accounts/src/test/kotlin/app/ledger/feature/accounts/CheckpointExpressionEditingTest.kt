package app.ledger.feature.accounts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CheckpointExpressionEditingTest {
    @Test
    fun `operator insertion advances the cursor before the next keyboard input`() {
        val afterOperator = checkpointExpressionAfterOperator(
            TextFieldValue("100", TextRange(3)),
            "+",
        )

        afterOperator.text shouldBe "100+"
        afterOperator.selection shouldBe TextRange(4)

        val afterTyping = afterOperator.copy(
            text = afterOperator.text.replaceRange(afterOperator.selection.start, afterOperator.selection.end, "50"),
            selection = TextRange(afterOperator.selection.start + 2),
        )
        afterTyping.text shouldBe "100+50"
        afterTyping.selection shouldBe TextRange(6)
    }

    @Test
    fun `operator replaces the active selection and normalizes display glyphs`() {
        checkpointExpressionAfterOperator(
            TextFieldValue("10050", TextRange(3, 5)),
            "×",
        ) shouldBe TextFieldValue("100*", TextRange(4))
    }

    @Test
    fun `delete removes the selection or the character before the cursor`() {
        checkpointExpressionAfterOperator(
            TextFieldValue("100+50", TextRange(4, 6)),
            "DELETE",
        ) shouldBe TextFieldValue("100+", TextRange(4))

        checkpointExpressionAfterOperator(
            TextFieldValue("100+", TextRange(4)),
            "DELETE",
        ) shouldBe TextFieldValue("100", TextRange(3))
    }
}
