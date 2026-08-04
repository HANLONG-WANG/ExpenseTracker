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
import app.ledger.finance.domain.InstallmentStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class InstallmentUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allNineteenFrozenStatesRenderAcrossWidthFontLocaleAndThemeMatrix() {
        val cases = cases()
        assertEquals(19, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, value) -> value.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(case.locale)))
                },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 2_100.dp)) {
                        InstallmentDestination(case.screen, InstallmentLoadState.Content(case.state), case.arguments, InstallmentDeviceFixtures.actions)
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
    fun scheduleConservationSettlementSimulationAndRefundDecisionRemainExplicitAtCompactLargeFont() {
        val plan = InstallmentDeviceFixtures.plan()
        assertEquals(1_200L, plan.currentSchedule.items.sumOf { it.principalMinor })
        assertEquals(0L, plan.currentSchedule.items.last().remainingPrincipalMinor)
        val state = InstallmentDeviceFixtures.state(
            "INS-006",
            InstallmentPresentation.REQUIRES_DECISION,
            InstallmentDeviceFixtures.refundedSnapshot(),
            InstallmentDeviceFixtures.planId,
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_800.dp)) {
                        InstallmentDestination("INS-006", InstallmentLoadState.Content(state), mapOf("planId" to InstallmentDeviceFixtures.planId.toString()), InstallmentDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.INSTALLMENT_REFUND).assertExists()
    }

    private fun cases(): List<Case> {
        val empty = InstallmentDeviceFixtures.snapshot(emptyList())
        val settled = InstallmentDeviceFixtures.snapshot(listOf(InstallmentDeviceFixtures.plan(InstallmentStatus.SETTLED)))
        val refunded = InstallmentDeviceFixtures.refundedSnapshot()
        val raw = listOf(
            Case("REC-027", "editing", LedgerTestTags.INSTALLMENT_SETUP, InstallmentDeviceFixtures.state("REC-027", InstallmentPresentation.EDITING)),
            Case("REC-027", "preview", LedgerTestTags.INSTALLMENT_SETUP, InstallmentDeviceFixtures.state("REC-027", InstallmentPresentation.PREVIEW)),
            Case("REC-027", "invalid", LedgerTestTags.INSTALLMENT_SETUP, InstallmentDeviceFixtures.state("REC-027", InstallmentPresentation.INVALID)),
            Case("REC-027", "saving", LedgerTestTags.INSTALLMENT_SETUP, InstallmentDeviceFixtures.state("REC-027", InstallmentPresentation.SAVING)),
            Case("INS-001", "content", LedgerTestTags.INSTALLMENT_LIST, InstallmentDeviceFixtures.state("INS-001", InstallmentPresentation.CONTENT)),
            Case("INS-001", "empty", LedgerTestTags.INSTALLMENT_LIST, InstallmentDeviceFixtures.state("INS-001", InstallmentPresentation.EMPTY, empty)),
            Case("INS-001", "completed", LedgerTestTags.INSTALLMENT_LIST, InstallmentDeviceFixtures.state("INS-001", InstallmentPresentation.COMPLETED, settled)),
            Case("INS-002", "create", LedgerTestTags.INSTALLMENT_EDITOR, InstallmentDeviceFixtures.state("INS-002", InstallmentPresentation.CREATE)),
            Case("INS-002", "edit", LedgerTestTags.INSTALLMENT_EDITOR, InstallmentDeviceFixtures.state("INS-002", InstallmentPresentation.EDIT, selectedPlanId = InstallmentDeviceFixtures.planId)),
            Case("INS-002", "invalid", LedgerTestTags.INSTALLMENT_EDITOR, InstallmentDeviceFixtures.state("INS-002", InstallmentPresentation.INVALID)),
            Case("INS-003", "active", LedgerTestTags.INSTALLMENT_DETAIL, InstallmentDeviceFixtures.state("INS-003", InstallmentPresentation.ACTIVE)),
            Case("INS-003", "completed", LedgerTestTags.INSTALLMENT_DETAIL, InstallmentDeviceFixtures.state("INS-003", InstallmentPresentation.COMPLETED, settled, InstallmentDeviceFixtures.planId)),
            Case("INS-003", "refundAdjusted", LedgerTestTags.INSTALLMENT_DETAIL, InstallmentDeviceFixtures.state("INS-003", InstallmentPresentation.REFUND_ADJUSTED, refunded, InstallmentDeviceFixtures.planId)),
            Case("INS-004", "content", LedgerTestTags.INSTALLMENT_SCHEDULE, InstallmentDeviceFixtures.state("INS-004", InstallmentPresentation.CONTENT)),
            Case("INS-005", "editing", LedgerTestTags.INSTALLMENT_SETTLEMENT, InstallmentDeviceFixtures.state("INS-005", InstallmentPresentation.EDITING)),
            Case("INS-005", "calculated", LedgerTestTags.INSTALLMENT_SETTLEMENT, InstallmentDeviceFixtures.state("INS-005", InstallmentPresentation.CALCULATED)),
            Case("INS-005", "invalid", LedgerTestTags.INSTALLMENT_SETTLEMENT, InstallmentDeviceFixtures.state("INS-005", InstallmentPresentation.INVALID)),
            Case("INS-006", "content", LedgerTestTags.INSTALLMENT_REFUND, InstallmentDeviceFixtures.state("INS-006", InstallmentPresentation.CONTENT, refunded, InstallmentDeviceFixtures.planId)),
            Case("INS-006", "requiresDecision", LedgerTestTags.INSTALLMENT_REFUND, InstallmentDeviceFixtures.state("INS-006", InstallmentPresentation.REQUIRES_DECISION, refunded, InstallmentDeviceFixtures.planId)),
        )
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
        val state: InstallmentFeatureState,
        val arguments: Map<String, String> = when {
            screen == "REC-027" -> mapOf("purchaseTransactionId" to InstallmentDeviceFixtures.purchaseId.toString())
            screen.startsWith("INS-") && screen !in setOf("INS-001", "INS-002") -> mapOf("planId" to InstallmentDeviceFixtures.planId.toString())
            screen == "INS-002" && state.selectedPlanId != null -> mapOf("planId" to state.selectedPlanId.toString())
            else -> emptyMap()
        },
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    private companion object {
        val EXPECTED = linkedMapOf(
            "REC-027" to setOf("editing", "preview", "invalid", "saving"),
            "INS-001" to setOf("content", "empty", "completed"),
            "INS-002" to setOf("create", "edit", "invalid"),
            "INS-003" to setOf("active", "completed", "refundAdjusted"),
            "INS-004" to setOf("content"),
            "INS-005" to setOf("editing", "calculated", "invalid"),
            "INS-006" to setOf("content", "requiresDecision"),
        )
    }
}
