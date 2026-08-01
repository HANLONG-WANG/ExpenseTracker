package app.ledger.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceToolchainContractTest {
    @Test
    fun macrobenchmarkAndBaselineProfileRulesAreAvailable() {
        assertEquals("MacrobenchmarkRule", MacrobenchmarkRule::class.java.simpleName)
        assertEquals("BaselineProfileRule", BaselineProfileRule::class.java.simpleName)
    }
}
