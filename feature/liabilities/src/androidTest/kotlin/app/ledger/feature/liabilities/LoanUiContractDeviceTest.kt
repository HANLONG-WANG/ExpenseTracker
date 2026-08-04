@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.feature.liabilities

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.domain.LoanStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LoanUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allFortyOneFrozenStatesRenderAcrossWidthFontLocaleAndThemeMatrix() {
        val cases = cases()
        assertEquals(41, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val localized = LocalContext.current.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 2_400.dp)) {
                        LoanDestination(case.screen, LoanLoadState.Content(case.state), case.arguments, LoanDeviceFixtures.actions)
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.tag).assertExists()
            composeRule.onRoot().assertExists()
        }
    }

    @Test
    fun combinationPrincipalAndSimulationRemainExplicitAtCompactTwoHundredPercentFont() {
        val contract = LoanDeviceFixtures.contract()
        assertEquals(contract.originalPrincipalMinor, contract.tranches.sumOf { it.originalPrincipalMinor })
        val simulation = LoanDeviceFixtures.simulation()
        assertEquals(simulation.remainingPrincipalBeforeMinor - simulation.prepaymentPrincipalMinor, simulation.after.items.sumOf { it.principalMinor })
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 2_000.dp)) {
                        LoanDestination("LOA-010", LoanLoadState.Content(LoanDeviceFixtures.state("LOA-010", LoanPresentation.RESULT)), arguments("LOA-010"), LoanDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.LOAN_SIMULATION).assertExists()
    }

    private fun cases(): List<Case> {
        val empty = LoanDeviceFixtures.snapshot(emptyList())
        val closed = LoanDeviceFixtures.snapshot(listOf(LoanDeviceFixtures.contract(LoanStatus.PAID_OFF)))
        val raw = EXPECTED.flatMap { (screen, states) ->
            states.map { stateName ->
                val presentation = PRESENTATIONS.getValue(stateName)
                val snapshot = when {
                    stateName == "empty" -> empty
                    stateName == "closed" -> closed
                    else -> LoanDeviceFixtures.snapshot()
                }
                Case(screen, stateName, TAGS.getValue(screen), LoanDeviceFixtures.state(screen, presentation, snapshot), arguments(screen))
            }
        }
        return raw.mapIndexed { index, case ->
            case.copy(
                width = listOf(320, 360, 480)[index % 3],
                fontScale = listOf(1f, 1.3f, 2f)[index % 3],
                locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3],
                theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
    }

    private data class Case(
        val screen: String,
        val stateName: String,
        val tag: String,
        val state: LoanFeatureState,
        val arguments: Map<String, String>,
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    companion object {
        val EXPECTED = linkedMapOf(
            "REC-017" to setOf("content"),
            "REC-018" to setOf("editing", "allocationError", "saving"),
            "REC-019" to setOf("editing", "principalExceeded", "sumMismatch", "saving"),
            "LIA-001" to setOf("content", "empty", "overdue"),
            "LOA-001" to setOf("content", "empty", "closed"),
            "LOA-002" to setOf("editing", "invalid", "generatingSchedule", "ready"),
            "LOA-003" to setOf("create", "edit"),
            "LOA-004" to setOf("editing", "invalid"),
            "LOA-005" to setOf("content", "overlapError", "empty"),
            "LOA-006" to setOf("generating", "content", "calculationError"),
            "LOA-007" to setOf("active", "closed", "overduePlanDifference", "multiTranche"),
            "LOA-008" to setOf("content", "empty"),
            "LOA-009" to setOf("content"),
            "LOA-010" to setOf("editing", "calculating", "result", "invalid"),
            "LOA-011" to setOf("content", "conflict"),
        )
        val PRESENTATIONS = mapOf(
            "content" to LoanPresentation.CONTENT, "empty" to LoanPresentation.EMPTY, "overdue" to LoanPresentation.OVERDUE,
            "closed" to LoanPresentation.CLOSED, "editing" to LoanPresentation.EDITING, "allocationError" to LoanPresentation.ALLOCATION_ERROR,
            "saving" to LoanPresentation.SAVING, "principalExceeded" to LoanPresentation.PRINCIPAL_EXCEEDED, "sumMismatch" to LoanPresentation.SUM_MISMATCH,
            "invalid" to LoanPresentation.INVALID, "generatingSchedule" to LoanPresentation.GENERATING_SCHEDULE, "ready" to LoanPresentation.READY,
            "create" to LoanPresentation.CREATE, "edit" to LoanPresentation.EDIT, "overlapError" to LoanPresentation.OVERLAP_ERROR,
            "generating" to LoanPresentation.GENERATING, "calculationError" to LoanPresentation.CALCULATION_ERROR, "active" to LoanPresentation.ACTIVE,
            "overduePlanDifference" to LoanPresentation.OVERDUE_PLAN_DIFFERENCE, "multiTranche" to LoanPresentation.MULTI_TRANCHE,
            "calculating" to LoanPresentation.CALCULATING, "result" to LoanPresentation.RESULT, "conflict" to LoanPresentation.CONFLICT,
        )
        val TAGS = mapOf(
            "REC-017" to LedgerTestTags.LOAN_OPERATION, "REC-018" to LedgerTestTags.LOAN_DISBURSEMENT, "REC-019" to LedgerTestTags.LOAN_PAYMENT,
            "LIA-001" to LedgerTestTags.LIABILITY_HOME, "LOA-001" to LedgerTestTags.LOAN_LIST, "LOA-002" to LedgerTestTags.LOAN_WIZARD,
            "LOA-003" to LedgerTestTags.LOAN_TRANCHE, "LOA-004" to LedgerTestTags.LOAN_TERMS, "LOA-005" to LedgerTestTags.LOAN_RATES,
            "LOA-006" to LedgerTestTags.LOAN_SCHEDULE_PREVIEW, "LOA-007" to LedgerTestTags.LOAN_DETAIL, "LOA-008" to LedgerTestTags.LOAN_SCHEDULE,
            "LOA-009" to LedgerTestTags.LOAN_PAYMENT_DETAIL, "LOA-010" to LedgerTestTags.LOAN_SIMULATION, "LOA-011" to LedgerTestTags.LOAN_SIMULATION_APPLY,
        )
        fun arguments(screen: String): Map<String, String> = when (screen) {
            "REC-018", "REC-019", "LOA-002", "LOA-006", "LOA-007", "LOA-008", "LOA-010" -> mapOf("contractId" to LoanDeviceFixtures.contractId.toString())
            "LOA-003", "LOA-004", "LOA-005" -> mapOf("contractId" to LoanDeviceFixtures.contractId.toString(), "trancheId" to LoanDeviceFixtures.trancheId.toString())
            "LOA-009" -> mapOf("transactionId" to LoanDeviceFixtures.transactionId.toString())
            "LOA-011" -> mapOf("contractId" to LoanDeviceFixtures.contractId.toString(), "simulationId" to LoanDeviceFixtures.simulationId.toString())
            else -> emptyMap()
        }
    }
}
