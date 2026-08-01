package app.ledger.core.money

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.ErrorPosition
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class AmountExpressionErrorKind {
    EMPTY,
    INPUT_TOO_LONG,
    TOO_MANY_TOKENS,
    NUMBER_TOO_LONG,
    INVALID_CHARACTER,
    INVALID_NUMBER,
    UNEXPECTED_TOKEN,
    MISSING_RIGHT_PARENTHESIS,
    NESTING_TOO_DEEP,
    DIVISION_BY_ZERO,
    FRACTION_NOT_ALLOWED,
    RESULT_NOT_POSITIVE,
    NUMERIC_RANGE,
}

data class AmountExpressionError(
    val kind: AmountExpressionErrorKind,
    val position: ErrorPosition,
) : DomainError {
    override val code: String = "AMOUNT_EXPRESSION_${kind.name}"
}

data class AmountExpression(
    val original: String,
    val normalized: String,
)

data class EvaluatedMoneyExpression(
    val expression: AmountExpression,
    val decimalResult: BigDecimal,
    val roundedMoney: Money,
)

data class ExpressionLimits(
    val maximumCharacters: Int = 256,
    val maximumTokens: Int = 256,
    val maximumNumberDigits: Int = 80,
    val maximumNestingDepth: Int = 32,
) {
    init {
        require(maximumCharacters > 0)
        require(maximumTokens > 0)
        require(maximumNumberDigits > 0)
        require(maximumNestingDepth > 0)
    }
}

class MoneyExpressionEvaluator(
    private val mathContext: MathContext = MathContext(DEFAULT_INTERMEDIATE_PRECISION, RoundingMode.HALF_EVEN),
    private val outputRoundingMode: RoundingMode = RoundingMode.HALF_EVEN,
    private val limits: ExpressionLimits = ExpressionLimits(),
) {
    init {
        require(mathContext.precision > 0) { "Expression MathContext must have finite precision" }
    }

    @Suppress("ReturnCount")
    fun evaluate(
        input: String,
        locale: Locale,
        currency: CurrencyMetadata,
    ): DomainResult<EvaluatedMoneyExpression> {
        if (input.length > limits.maximumCharacters) {
            return failure(AmountExpressionErrorKind.INPUT_TOO_LONG, limits.maximumCharacters)
        }
        val tokenization = Tokenizer(input, locale, limits).tokenize()
        val tokenized = when (tokenization) {
            is DomainResult.Success -> tokenization.value
            is DomainResult.Failure -> return tokenization
        }
        if (currency.fractionDigits == 0) {
            tokenized.tokens.filterIsInstance<Token.Number>().firstOrNull(Token.Number::hasDecimal)?.let { number ->
                return failure(AmountExpressionErrorKind.FRACTION_NOT_ALLOWED, number.position)
            }
        }
        val parsing = Parser(tokenized.tokens, limits.maximumNestingDepth).parse()
        val expression = when (parsing) {
            is DomainResult.Success -> parsing.value
            is DomainResult.Failure -> return parsing
        }
        val decimalResult = when (val evaluation = evaluateNode(expression, 0)) {
            is DomainResult.Success -> evaluation.value
            is DomainResult.Failure -> return evaluation
        }
        if (decimalResult.signum() <= 0) {
            return failure(AmountExpressionErrorKind.RESULT_NOT_POSITIVE, expression.position)
        }
        return when (val rounded = Money.fromMajor(decimalResult, currency, outputRoundingMode)) {
            is DomainResult.Success -> DomainResult.Success(
                EvaluatedMoneyExpression(
                    expression = AmountExpression(input, tokenized.normalized),
                    decimalResult = decimalResult,
                    roundedMoney = rounded.value,
                ),
            )
            is DomainResult.Failure -> failure(AmountExpressionErrorKind.NUMERIC_RANGE, expression.position)
        }
    }

    private fun evaluateNode(node: Node, depth: Int): DomainResult<BigDecimal> {
        if (depth > limits.maximumNestingDepth) {
            return failure(AmountExpressionErrorKind.NESTING_TOO_DEEP, node.position)
        }
        return try {
            when (node) {
                is Node.Number -> DomainResult.Success(BigDecimal(node.value))
                is Node.Unary -> {
                    when (val operand = evaluateNode(node.operand, depth + 1)) {
                        is DomainResult.Success -> DomainResult.Success(
                            if (node.operator == Operator.SUBTRACT) {
                                operand.value.negate(mathContext)
                            } else {
                                operand.value.plus(mathContext)
                            },
                        )
                        is DomainResult.Failure -> operand
                    }
                }
                is Node.Binary -> evaluateBinary(node, depth)
            }
        } catch (_: ArithmeticException) {
            failure(AmountExpressionErrorKind.NUMERIC_RANGE, node.position)
        } catch (_: NumberFormatException) {
            failure(AmountExpressionErrorKind.INVALID_NUMBER, node.position)
        }
    }

    @Suppress("ReturnCount")
    private fun evaluateBinary(node: Node.Binary, depth: Int): DomainResult<BigDecimal> {
        val left = when (val result = evaluateNode(node.left, depth + 1)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val right = when (val result = evaluateNode(node.right, depth + 1)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        if (node.operator == Operator.DIVIDE && right.signum() == 0) {
            return failure(AmountExpressionErrorKind.DIVISION_BY_ZERO, node.position)
        }
        val value = when (node.operator) {
            Operator.ADD -> left.add(right, mathContext)
            Operator.SUBTRACT -> left.subtract(right, mathContext)
            Operator.MULTIPLY -> left.multiply(right, mathContext)
            Operator.DIVIDE -> left.divide(right, mathContext)
        }
        return DomainResult.Success(value)
    }

    private fun failure(kind: AmountExpressionErrorKind, position: Int): DomainResult.Failure = DomainResult.Failure(AmountExpressionError(kind, ErrorPosition(position)))

    private data class TokenizedInput(
        val normalized: String,
        val tokens: List<Token>,
    )

    private sealed interface Token {
        val position: Int

        data class Number(
            val value: String,
            val hasDecimal: Boolean,
            override val position: Int,
        ) : Token

        data class Symbol(val operator: Operator, override val position: Int) : Token

        data class LeftParenthesis(override val position: Int) : Token

        data class RightParenthesis(override val position: Int) : Token

        data class End(override val position: Int) : Token
    }

    private enum class Operator {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
    }

    private sealed interface Node {
        val position: Int

        data class Number(val value: String, override val position: Int) : Node

        data class Unary(
            val operator: Operator,
            val operand: Node,
            override val position: Int,
        ) : Node

        data class Binary(
            val left: Node,
            val operator: Operator,
            val right: Node,
            override val position: Int,
        ) : Node
    }

    private class Tokenizer(
        private val input: String,
        locale: Locale,
        private val limits: ExpressionLimits,
    ) {
        private val localeDecimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator

        @Suppress("NestedBlockDepth", "ReturnCount")
        fun tokenize(): DomainResult<TokenizedInput> {
            val characters = ArrayList<NormalizedCharacter>()
            input.forEachIndexed { index, value ->
                if (!value.isWhitespace()) {
                    val normalized = normalize(value)
                        ?: return failure(AmountExpressionErrorKind.INVALID_CHARACTER, index)
                    characters += NormalizedCharacter(normalized, index)
                }
            }
            if (characters.isEmpty()) return failure(AmountExpressionErrorKind.EMPTY, 0)

            val tokens = ArrayList<Token>()
            var cursor = 0
            while (cursor < characters.size) {
                val character = characters[cursor]
                if (character.value.isDigit() || character.value == '.') {
                    val number = StringBuilder()
                    val start = character.originalPosition
                    var hasDecimal = false
                    var digitCount = 0
                    while (cursor < characters.size) {
                        val candidate = characters[cursor]
                        when {
                            candidate.value.isDigit() -> {
                                digitCount = Math.addExact(digitCount, 1)
                                if (digitCount > limits.maximumNumberDigits) {
                                    return failure(AmountExpressionErrorKind.NUMBER_TOO_LONG, candidate.originalPosition)
                                }
                                number.append(candidate.value)
                                cursor++
                            }
                            candidate.value == '.' -> {
                                if (hasDecimal) {
                                    return failure(AmountExpressionErrorKind.INVALID_NUMBER, candidate.originalPosition)
                                }
                                hasDecimal = true
                                number.append('.')
                                cursor++
                            }
                            else -> break
                        }
                    }
                    if (digitCount == 0) return failure(AmountExpressionErrorKind.INVALID_NUMBER, start)
                    tokens += Token.Number(number.toString(), hasDecimal, start)
                } else {
                    tokens += when (character.value) {
                        '+' -> Token.Symbol(Operator.ADD, character.originalPosition)
                        '-' -> Token.Symbol(Operator.SUBTRACT, character.originalPosition)
                        '*' -> Token.Symbol(Operator.MULTIPLY, character.originalPosition)
                        '/' -> Token.Symbol(Operator.DIVIDE, character.originalPosition)
                        '(' -> Token.LeftParenthesis(character.originalPosition)
                        ')' -> Token.RightParenthesis(character.originalPosition)
                        else -> return failure(AmountExpressionErrorKind.INVALID_CHARACTER, character.originalPosition)
                    }
                    cursor++
                }
                if (tokens.size >= limits.maximumTokens) {
                    return failure(AmountExpressionErrorKind.TOO_MANY_TOKENS, character.originalPosition)
                }
            }
            tokens += Token.End(input.length)
            return DomainResult.Success(
                TokenizedInput(
                    normalized = characters.joinToString(separator = "") { it.value.toString() },
                    tokens = tokens.toList(),
                ),
            )
        }

        private fun normalize(value: Char): Char? = when (value) {
            in '0'..'9', '+', '-', '*', '/', '(', ')' -> value
            in '\uff10'..'\uff19' -> '0' + (value - '\uff10')
            '\uff0b', '\ufe62' -> '+'
            '\uff0d', '\u2212', '\ufe63' -> '-'
            '\uff0a', '\u00d7' -> '*'
            '\uff0f', '\u00f7' -> '/'
            '\uff08' -> '('
            '\uff09' -> ')'
            '.', '\uff0e' -> '.'
            ',', '\uff0c' -> if (localeDecimalSeparator == ',') '.' else null
            else -> null
        }

        private fun failure(kind: AmountExpressionErrorKind, position: Int): DomainResult.Failure = DomainResult.Failure(AmountExpressionError(kind, ErrorPosition(position)))

        private data class NormalizedCharacter(val value: Char, val originalPosition: Int)
    }

    private class Parser(
        private val tokens: List<Token>,
        private val maximumNestingDepth: Int,
    ) {
        private var cursor = 0

        fun parse(): DomainResult<Node> {
            val expression = when (val result = parseExpression(0, 0)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return result
            }
            val remaining = current()
            return if (remaining is Token.End) {
                DomainResult.Success(expression)
            } else {
                failure(AmountExpressionErrorKind.UNEXPECTED_TOKEN, remaining.position)
            }
        }

        @Suppress("ReturnCount")
        private fun parseExpression(minimumBindingPower: Int, depth: Int): DomainResult<Node> {
            if (depth > maximumNestingDepth) {
                return failure(AmountExpressionErrorKind.NESTING_TOO_DEEP, current().position)
            }
            var left = when (val result = parsePrefix(depth)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return result
            }
            var symbol = eligibleSymbol(minimumBindingPower)
            while (symbol != null) {
                val (_, rightPower) = bindingPower(symbol.operator)
                cursor++
                val right = when (val result = parseExpression(rightPower, depth + 1)) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return result
                }
                left = Node.Binary(left, symbol.operator, right, symbol.position)
                symbol = eligibleSymbol(minimumBindingPower)
            }
            return DomainResult.Success(left)
        }

        @Suppress("ReturnCount")
        private fun parsePrefix(depth: Int): DomainResult<Node> {
            val token = current()
            cursor++
            return when (token) {
                is Token.Number -> DomainResult.Success(Node.Number(token.value, token.position))
                is Token.Symbol -> if (token.operator == Operator.ADD || token.operator == Operator.SUBTRACT) {
                    when (val operand = parseExpression(PREFIX_BINDING_POWER, depth + 1)) {
                        is DomainResult.Success -> DomainResult.Success(
                            Node.Unary(token.operator, operand.value, token.position),
                        )
                        is DomainResult.Failure -> operand
                    }
                } else {
                    failure(AmountExpressionErrorKind.UNEXPECTED_TOKEN, token.position)
                }
                is Token.LeftParenthesis -> {
                    val nested = when (val result = parseExpression(0, depth + 1)) {
                        is DomainResult.Success -> result.value
                        is DomainResult.Failure -> return result
                    }
                    val closing = current()
                    if (closing !is Token.RightParenthesis) {
                        return failure(AmountExpressionErrorKind.MISSING_RIGHT_PARENTHESIS, closing.position)
                    }
                    cursor++
                    DomainResult.Success(nested)
                }
                is Token.RightParenthesis, is Token.End ->
                    failure(AmountExpressionErrorKind.UNEXPECTED_TOKEN, token.position)
            }
        }

        private fun current(): Token = tokens[cursor.coerceAtMost(tokens.lastIndex)]

        private fun eligibleSymbol(minimumBindingPower: Int): Token.Symbol? {
            val symbol = current() as? Token.Symbol ?: return null
            return symbol.takeIf { bindingPower(it.operator).first >= minimumBindingPower }
        }

        private fun bindingPower(operator: Operator): Pair<Int, Int> = when (operator) {
            Operator.ADD, Operator.SUBTRACT -> ADDITIVE_LEFT_POWER to ADDITIVE_RIGHT_POWER
            Operator.MULTIPLY, Operator.DIVIDE -> MULTIPLICATIVE_LEFT_POWER to MULTIPLICATIVE_RIGHT_POWER
        }

        private fun failure(kind: AmountExpressionErrorKind, position: Int): DomainResult.Failure = DomainResult.Failure(AmountExpressionError(kind, ErrorPosition(position)))

        private companion object {
            const val ADDITIVE_LEFT_POWER = 1
            const val ADDITIVE_RIGHT_POWER = 2
            const val MULTIPLICATIVE_LEFT_POWER = 3
            const val MULTIPLICATIVE_RIGHT_POWER = 4
            const val PREFIX_BINDING_POWER = 5
        }
    }
}

private const val DEFAULT_INTERMEDIATE_PRECISION = 34
