package app.ledger.core.designsystem

import app.ledger.core.designsystem.tokens.GeneratedLedgerTokenContract
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerTokenContractTest {
    @Test
    fun `complete generated scalar contract retains JSON identity`() {
        GeneratedLedgerTokenContract.VERSION shouldBe "1.0.0"
        GeneratedLedgerTokenContract.SCALAR_COUNT shouldBe 434
        GeneratedLedgerTokenContract.scalarValues.size shouldBe 434
        GeneratedLedgerTokenContract.CANONICAL_SHA256 shouldBe
            "f976230cc3219a47b8e237247633fda3aa1559aa21a7bf2b4667a4d3df195f45"
    }

    @Test
    fun `typed dimensions typography spacing shape and motion map exact token values`() {
        val dimensions = LedgerTokenMapping.dimensions
        dimensions.touchTargetMin.value shouldBe 48f
        dimensions.categoryColumns(320f.dpForTest()) shouldBe 3
        dimensions.categoryColumns(360f.dpForTest()) shouldBe 4
        dimensions.categoryColumns(480f.dpForTest()) shouldBe 5
        dimensions.horizontalPadding(320f.dpForTest()).value shouldBe 12f
        dimensions.horizontalPadding(360f.dpForTest()).value shouldBe 16f
        LedgerTokenMapping.spacing.giant.value shouldBe 64f
        LedgerTokenMapping.typography.amountHero.fontSize.value shouldBe 40f
        LedgerTokenMapping.typography.amountHero.lineHeight.value shouldBe 48f
        LedgerTokenMapping.motion(false).standardMs shouldBe 220
        LedgerTokenMapping.motion(true).duration(320) shouldBe 80
        LedgerTokenMapping.goldenPalette shouldHaveSize 208
    }

    @Test
    fun `sixteen category pairs pass icon contrast in both themes`() {
        listOf(LedgerTokenMapping.lightColors(), LedgerTokenMapping.darkColors()).forEach { colors ->
            colors.categoryPalette shouldHaveSize 16
            colors.categoryPalette.forEach { pair ->
                LedgerContrast.ratio(pair.foreground, pair.container) shouldBeGreaterThanOrEqual 3.0
            }
        }
    }

    @Test
    fun `semantic colors pass text and icon contrast in both themes`() {
        listOf(LedgerTokenMapping.lightColors(), LedgerTokenMapping.darkColors()).forEach { colors ->
            listOf(colors.positive, colors.warning, colors.danger, colors.info, colors.neutralTransaction).forEach { semantic ->
                LedgerContrast.ratio(semantic.base, semantic.onBase) shouldBeGreaterThanOrEqual 3.0
                LedgerContrast.ratio(semantic.container, semantic.onContainer) shouldBeGreaterThanOrEqual 4.5
            }
        }
    }

    @Test
    fun `chart categorical axis selection and sequential content pass contrast contract`() {
        listOf(LedgerTokenMapping.lightColors(), LedgerTokenMapping.darkColors()).forEach { colors ->
            colors.chart.categorical.forEach { series ->
                LedgerContrast.ratio(series, colors.material.background) shouldBeGreaterThanOrEqual 3.0
            }
            LedgerContrast.ratio(colors.chart.axis, colors.material.background) shouldBeGreaterThanOrEqual 4.5
            LedgerContrast.ratio(colors.chart.selection, colors.material.background) shouldBeGreaterThanOrEqual 3.0
            colors.chart.sequentialTeal.forEach { cell ->
                LedgerContrast.ratio(LedgerContrast.accessibleContent(cell), cell) shouldBeGreaterThanOrEqual 4.5
            }
        }
    }

    @Test
    fun `dynamic shell replacement cannot change financial semantic category or chart tokens`() {
        val base = LedgerTokenMapping.lightColors()
        val shellReplacement = base.material.copy(primary = androidx.compose.ui.graphics.Color.Magenta)
        val dynamic = base.copy(material = shellReplacement)
        dynamic.positive shouldBe base.positive
        dynamic.warning shouldBe base.warning
        dynamic.danger shouldBe base.danger
        dynamic.info shouldBe base.info
        dynamic.categoryPalette shouldBe base.categoryPalette
        dynamic.chart shouldBe base.chart
        dynamic.material.primary shouldBe androidx.compose.ui.graphics.Color.Magenta
    }

    @Test
    fun `pie compatibility is deterministic at six categories`() {
        VisualizationCompatibility.resolve(LedgerChartType.PIE, 6) shouldBe LedgerChartType.PIE
        VisualizationCompatibility.resolve(LedgerChartType.PIE, 7) shouldBe LedgerChartType.COLUMN
    }

    @Test
    fun `test tags accept semantic IDs and reject values or sensitive names`() {
        LedgerTestTags.requireStable("transaction_amount") shouldBe "transaction_amount"
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { LedgerTestTags.requireStable("amount-1250") }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { LedgerTestTags.requireStable("card_number_4111") }
    }

    private fun Float.dpForTest(): androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(this)
}
