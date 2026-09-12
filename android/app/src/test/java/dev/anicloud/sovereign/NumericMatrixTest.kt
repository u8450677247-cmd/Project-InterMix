package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NumericMatrixTest {
    @Test
    fun decimalArithmeticUsesControllerPrecedence() {
        assertEquals("14", DeterministicCalculator.evaluate("2 + 3 * 4").result)
        assertEquals("30", DeterministicCalculator.evaluate("200 * 15%").result)
        assertEquals("0.125", DeterministicCalculator.evaluate("2 ^ -3").result)
        assertEquals("-4", DeterministicCalculator.evaluate("-2 ^ 2").result)
    }

    @Test
    fun decimalDivisionHasStablePrecision() {
        assertEquals(
            "0.3333333333333333333333333333333333",
            DeterministicCalculator.evaluate("1 / 3").result,
        )
    }

    @Test
    fun unsafeOrUndefinedExpressionsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicCalculator.evaluate("1 / 0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicCalculator.evaluate("Runtime.getRuntime()")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicCalculator.evaluate("2 ^ 101")
        }
    }
}
