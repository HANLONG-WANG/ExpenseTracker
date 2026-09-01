package app.ledger.core.designsystem

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MoneyExpressionEditingTest {
    @Test
    fun `operators insert at cursor and advance selection`() {
        listOf("+" to "+", "−" to "-", "×" to "*", "÷" to "/").forEach { (button, expected) ->
            val edited = MoneyExpressionEditing.apply(
                TextFieldValue("1234", selection = TextRange(2)),
                button,
            )
            assertEquals("12${expected}34", edited.text)
            assertEquals(TextRange(3), edited.selection)
        }
    }

    @Test
    fun `operator replaces selection and delete respects cursor`() {
        val replaced = MoneyExpressionEditing.apply(
            TextFieldValue("1234", selection = TextRange(1, 3)),
            "×",
        )
        assertEquals("1*4", replaced.text)
        assertEquals(TextRange(2), replaced.selection)

        val deleted = MoneyExpressionEditing.apply(
            TextFieldValue("12+34", selection = TextRange(3)),
            "DELETE",
        )
        assertEquals("1234", deleted.text)
        assertEquals(TextRange(2), deleted.selection)
    }
}
