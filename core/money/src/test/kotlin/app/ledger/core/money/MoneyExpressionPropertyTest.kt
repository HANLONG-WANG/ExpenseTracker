package app.ledger.core.money

import app.ledger.core.common.DomainResult
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

class MoneyExpressionPropertyTest {
    private val evaluator = MoneyExpressionEvaluator()
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val jpy = metadata("JPY")
    private val usd = metadata("USD")

    @Test
    fun `legal integer expressions obey precedence for generated operands`() = runTest {
        checkAll(iterations = 1_000, Arb.long(1L, 1_000_000L), Arb.long(1L, 1_000_000L)) { left, right ->
            val result = success(evaluator.evaluate("$left + $right * 2", Locale.JAPAN, jpy))

            result.roundedMoney shouldBe Money(Math.addExact(left, Math.multiplyExact(right, 2L)), jpy.code)
            result.decimalResult shouldBe BigDecimal.valueOf(left).add(
                BigDecimal.valueOf(right).multiply(BigDecimal("2")),
            )
        }
    }

    @Test
    fun `normalizes full-width digits symbols parentheses and spaces`() {
        val result = success(evaluator.evaluate(" （１２＋３） × ２ ", Locale.SIMPLIFIED_CHINESE, jpy))

        result.expression.normalized shouldBe "(12+3)*2"
        result.roundedMoney shouldBe Money(30L, jpy.code)
    }

    @Test
    fun `supports Chinese Japanese English and a comma decimal locale`() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.JAPAN, Locale.US).forEach { locale ->
            success(evaluator.evaluate("12.34+0.01", locale, usd)).roundedMoney shouldBe Money(1235L, usd.code)
        }
        success(evaluator.evaluate("12,34＋0,01", Locale.GERMANY, usd)).roundedMoney shouldBe Money(1235L, usd.code)
    }

    @Test
    fun `division is decimal and rounds once to the currency minimum unit`() {
        val oneThird = success(evaluator.evaluate("1/3", Locale.US, usd))
        val halfEvenDown = success(evaluator.evaluate("1.005", Locale.US, usd))
        val halfEvenUp = success(evaluator.evaluate("1.015", Locale.US, usd))

        oneThird.roundedMoney shouldBe Money(33L, usd.code)
        halfEvenDown.roundedMoney shouldBe Money(100L, usd.code)
        halfEvenUp.roundedMoney shouldBe Money(102L, usd.code)
    }

    @Test
    fun `zero-decimal currency rejects explicit decimal literals at their source position`() {
        error(evaluator.evaluate("10+1.0", Locale.JAPAN, jpy)) shouldBe AmountExpressionError(
            AmountExpressionErrorKind.FRACTION_NOT_ALLOWED,
            app.ledger.core.common.ErrorPosition(3),
        )
        success(evaluator.evaluate("1/2+1", Locale.JAPAN, jpy)).roundedMoney shouldBe Money(2L, jpy.code)
    }

    @Test
    fun `illegal grammar and operators return precise typed errors`() {
        val cases = mapOf(
            "" to AmountExpressionErrorKind.EMPTY,
            "1/0" to AmountExpressionErrorKind.DIVISION_BY_ZERO,
            "1+(2*3" to AmountExpressionErrorKind.MISSING_RIGHT_PARENTHESIS,
            "2^3" to AmountExpressionErrorKind.INVALID_CHARACTER,
            "1%" to AmountExpressionErrorKind.INVALID_CHARACTER,
            "sqrt(4)" to AmountExpressionErrorKind.INVALID_CHARACTER,
            "0" to AmountExpressionErrorKind.RESULT_NOT_POSITIVE,
            "1-2" to AmountExpressionErrorKind.RESULT_NOT_POSITIVE,
            "1..2" to AmountExpressionErrorKind.INVALID_NUMBER,
        )

        cases.forEach { (input, expected) ->
            (error(evaluator.evaluate(input, Locale.US, usd)) as AmountExpressionError).kind shouldBe expected
        }
        val missingParenthesis = error(evaluator.evaluate("1+(2*3", Locale.US, usd)) as AmountExpressionError
        missingParenthesis.position.zeroBasedIndex shouldBe 6
        missingParenthesis.position.oneBasedPosition shouldBe 7
    }

    @Test
    fun `extreme values and bounded grammar fail without wrapping or script execution`() {
        val huge = "9".repeat(80)
        (error(evaluator.evaluate(huge, Locale.US, usd)) as AmountExpressionError).kind shouldBe
            AmountExpressionErrorKind.NUMERIC_RANGE
        val tooLong = "1+".repeat(129)
        (error(evaluator.evaluate(tooLong, Locale.US, usd)) as AmountExpressionError).kind shouldBe
            AmountExpressionErrorKind.INPUT_TOO_LONG
        val tooDeep = "(".repeat(40) + "1" + ")".repeat(40)
        (error(evaluator.evaluate(tooDeep, Locale.US, usd)) as AmountExpressionError).kind shouldBe
            AmountExpressionErrorKind.NESTING_TOO_DEEP
    }

    @Test
    fun `unary operators remain deterministic`() {
        success(evaluator.evaluate("5--2", Locale.US, usd)).roundedMoney shouldBe Money(700L, usd.code)
        success(evaluator.evaluate("-(2-5)", Locale.US, usd)).roundedMoney shouldBe Money(300L, usd.code)
    }

    private fun metadata(value: String): CurrencyMetadata = (catalog.require((CurrencyCode.parse(value) as DomainResult.Success).value) as DomainResult.Success).value

    private fun success(result: DomainResult<EvaluatedMoneyExpression>): EvaluatedMoneyExpression = (result as DomainResult.Success).value

    private fun error(result: DomainResult<EvaluatedMoneyExpression>) = (result as DomainResult.Failure).error
}
