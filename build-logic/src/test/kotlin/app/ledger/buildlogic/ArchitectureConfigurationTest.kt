package app.ledger.buildlogic

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ArchitectureConfigurationTest {
    @Test
    fun `production variants contribute to the frozen module graph`() {
        listOf(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
            "debugImplementation",
            "releaseImplementation",
            "testedApks",
            "benchmarkTestedApks",
        )
            .forEach { configuration ->
                configuration.contributesToProductionArchitecture() shouldBe true
            }
    }

    @Test
    fun `test fixtures do not become production module edges`() {
        listOf("testImplementation", "androidTestImplementation", "testFixturesApi", "kspTestKotlin")
            .forEach { configuration ->
                configuration.contributesToProductionArchitecture() shouldBe false
            }
    }
}
