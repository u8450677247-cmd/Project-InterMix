package dev.anicloud.sovereign.prototype

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private const val NumericEngineVersion = "decimal128-v1"
private const val MaxNumericExpressionCharacters = 256
private const val MaxAbsolutePower = 100

data class NumericCalculationProposal(
    val expression: String,
    val reason: String = "",
)

data class VerifiedCalculation(
    val expression: String,
    val result: String,
    val engine: String = NumericEngineVersion,
)

data class NumericCalculationRecord(
    val id: Long,
    val expression: String,
    val result: String,
    val engine: String,
    val reason: String,
    val createdAt: String,
)

/**
 * Deliberately small arithmetic grammar. Model text may request a calculation, but only this
 * controller returns a value. There is no eval, reflection, script execution, or network path.
 *
 * Grammar: decimal literals, parentheses, unary +/- and +, -, *, /, ^. A postfix % means divide
 * by one hundred. Powers must be whole numbers from -100 through 100.
 */
object DeterministicCalculator {
    private val mathContext = MathContext(34, RoundingMode.HALF_EVEN)

    fun evaluate(raw: String): VerifiedCalculation {
        val expression = raw
            .replace('\u2212', '-')
            .replace('\u00d7', '*')
            .replace('\u00f7', '/')
            .replace("\u0000", "")
            .trim()
        require(expression.isNotBlank()) { "Enter an arithmetic expression." }
        require(expression.length <= MaxNumericExpressionCharacters) {
            "Calculator expressions are limited to $MaxNumericExpressionCharacters characters."
        }
        require(expression.none { it.code < 0x20 && !it.isWhitespace() }) {
            "The calculator expression contains a control character."
        }
        val value = Parser(expression).parse()
        val normalized = value.round(mathContext).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }
        val result = normalized.toPlainString()
        require(result.length <= 512) { "The verified result is too large to display safely." }
        return VerifiedCalculation(expression = expression, result = result)
    }

    private class Parser(private val source: String) {
        private var cursor = 0

        fun parse(): BigDecimal {
            val value = parseExpression()
            skipWhitespace()
            require(cursor == source.length) {
                "Unexpected calculator token at position ${cursor + 1}."
            }
            return value
        }

        private fun parseExpression(): BigDecimal {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('+') -> value.add(parseTerm(), mathContext)
                    consume('-') -> value.subtract(parseTerm(), mathContext)
                    else -> return value
                }
            }
        }

        private fun parseTerm(): BigDecimal {
            var value = parseUnary()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('*') -> value.multiply(parseUnary(), mathContext)
                    consume('/') -> {
                        val divisor = parseUnary()
                        require(divisor.compareTo(BigDecimal.ZERO) != 0) { "Division by zero is undefined." }
                        value.divide(divisor, mathContext)
                    }
                    else -> return value
                }
            }
        }

        private fun parsePower(): BigDecimal {
            val base = parsePostfix()
            skipWhitespace()
            if (!consume('^')) return base
            val exponentValue = parseUnary()
            val normalizedExponent = exponentValue.stripTrailingZeros()
            require(normalizedExponent.scale() <= 0) { "Powers require a whole-number exponent." }
            val exponent = runCatching { normalizedExponent.intValueExact() }
                .getOrElse { throw IllegalArgumentException("The exponent is outside the supported range.") }
            require(exponent in -MaxAbsolutePower..MaxAbsolutePower) {
                "Powers are limited to -$MaxAbsolutePower through $MaxAbsolutePower."
            }
            if (exponent >= 0) return base.pow(exponent, mathContext)
            require(base.compareTo(BigDecimal.ZERO) != 0) { "Zero cannot have a negative exponent." }
            return BigDecimal.ONE.divide(base.pow(-exponent, mathContext), mathContext)
        }

        private fun parseUnary(): BigDecimal {
            skipWhitespace()
            return when {
                consume('+') -> parseUnary()
                consume('-') -> parseUnary().negate(mathContext)
                else -> parsePower()
            }
        }

        private fun parsePostfix(): BigDecimal {
            var value = parsePrimary()
            skipWhitespace()
            while (consume('%')) {
                value = value.divide(BigDecimal("100"), mathContext)
                skipWhitespace()
            }
            return value
        }

        private fun parsePrimary(): BigDecimal {
            skipWhitespace()
            if (consume('(')) {
                val value = parseExpression()
                skipWhitespace()
                require(consume(')')) { "A closing parenthesis is missing." }
                return value
            }
            return parseNumber()
        }

        private fun parseNumber(): BigDecimal {
            skipWhitespace()
            val start = cursor
            var decimalSeen = false
            while (cursor < source.length) {
                val character = source[cursor]
                when {
                    character.isDigit() -> cursor++
                    character == '.' && !decimalSeen -> {
                        decimalSeen = true
                        cursor++
                    }
                    else -> break
                }
            }
            val token = source.substring(start, cursor)
            require(token.any(Char::isDigit)) { "A decimal number is required at position ${start + 1}." }
            return runCatching { token.toBigDecimal(mathContext) }
                .getOrElse { throw IllegalArgumentException("Invalid decimal number at position ${start + 1}.") }
        }

        private fun consume(character: Char): Boolean {
            if (cursor >= source.length || source[cursor] != character) return false
            cursor++
            return true
        }

        private fun skipWhitespace() {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        }
    }
}
