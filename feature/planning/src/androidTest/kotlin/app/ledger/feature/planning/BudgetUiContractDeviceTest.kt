@file:Suppress("LongMethod", "MaxLineLength")

package app.ledger.feature.planning

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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.application.BudgetProjectionReadiness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BudgetUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allTwentyThreeFrozenRequiredStatesRenderAcrossResponsiveAccessibleLocalizedMatrix() {
        val cases = cases()
        assertEquals(23, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = base.createConfigurationContext(Configuration(configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) })
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalDensity provides Density(1f, case.fontScale)) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 1_700.dp)) {
                        BudgetDestination(case.screen, BudgetLoadState.Content(case.featureState), case.arguments, BudgetDeviceFixtures.actions)
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.expectedTag).assertExists()
        }
    }

    @Test
    fun constraintEvidenceAndSaveRemainReachableAtCompactWidthAndTwoHundredPercentFont() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 720.dp)) {
                        BudgetDestination("BUD-002", BudgetLoadState.Content(BudgetDeviceFixtures.constraintState()), emptyMap(), BudgetDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.BUDGET_EDITOR).performScrollToNode(hasTestTag(LedgerTestTags.BUDGET_CONSTRAINT_METERS))
        composeRule.onNodeWithTag(LedgerTestTags.BUDGET_CONSTRAINT_METERS).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.BUDGET_EDITOR).performScrollToNode(hasText("保存", substring = true) or hasText("Save", substring = true) or hasText("保存する", substring = true))
        composeRule.onRoot().assertExists()
    }

    private fun cases(): List<Case> {
        val current = BudgetDeviceFixtures.configured()
        val historical = BudgetDeviceFixtures.configured(
            BudgetDeviceFixtures.Configuration(month = YearMonth.of(2026, 7)),
        )
        val future = BudgetDeviceFixtures.configured(
            BudgetDeviceFixtures.Configuration(month = YearMonth.of(2026, 9)),
        )
        val raw = listOf(
            Case("BUD-001", "configured", LedgerTestTags.BUDGET_HOME, BudgetDeviceFixtures.state(current)),
            Case("BUD-001", "notConfigured", LedgerTestTags.BUDGET_HOME, BudgetDeviceFixtures.state(BudgetDeviceFixtures.notConfigured(), BudgetPresentation.NOT_CONFIGURED)),
            Case(
                "BUD-001",
                "recalculating",
                LedgerTestTags.BUDGET_HOME,
                BudgetDeviceFixtures.state(
                    BudgetDeviceFixtures.configured(
                        BudgetDeviceFixtures.Configuration(readiness = BudgetProjectionReadiness.RECALCULATING),
                    ),
                    BudgetPresentation.RECALCULATING,
                ),
            ),
            Case("BUD-001", "historical", LedgerTestTags.BUDGET_HOME, BudgetDeviceFixtures.state(historical, BudgetPresentation.HISTORICAL)),
            Case("BUD-001", "future", LedgerTestTags.BUDGET_HOME, BudgetDeviceFixtures.state(future, BudgetPresentation.FUTURE)),
            Case("BUD-002", "editing", LedgerTestTags.BUDGET_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.EDITING)),
            Case("BUD-002", "constraintError", LedgerTestTags.BUDGET_EDITOR, BudgetDeviceFixtures.constraintState()),
            Case("BUD-002", "saving", LedgerTestTags.BUDGET_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.SAVING)),
            Case("BUD-002", "historyRecalculationWarning", LedgerTestTags.BUDGET_EDITOR, BudgetDeviceFixtures.state(historical, BudgetPresentation.HISTORY_RECALCULATION_WARNING)),
            Case("BUD-003", "editing", LedgerTestTags.BUDGET_CATEGORY_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.EDITING), mapOf("categoryId" to BudgetDeviceFixtures.root.toString())),
            Case("BUD-003", "constraintError", LedgerTestTags.BUDGET_CATEGORY_EDITOR, BudgetDeviceFixtures.constraintState(), mapOf("categoryId" to BudgetDeviceFixtures.root.toString())),
            Case("BUD-004", "content", LedgerTestTags.BUDGET_ADJUSTMENTS, BudgetDeviceFixtures.state(current, BudgetPresentation.CONTENT)),
            Case(
                "BUD-004",
                "empty",
                LedgerTestTags.BUDGET_ADJUSTMENTS,
                BudgetDeviceFixtures.state(
                    BudgetDeviceFixtures.configured(
                        BudgetDeviceFixtures.Configuration(withAdjustments = false),
                    ),
                    BudgetPresentation.EMPTY,
                ),
            ),
            Case("BUD-005", "editing", LedgerTestTags.BUDGET_ADJUSTMENT_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.EDITING), mapOf("type" to "TRANSFER")),
            Case("BUD-005", "invalid", LedgerTestTags.BUDGET_ADJUSTMENT_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.INVALID), mapOf("type" to "ADD")),
            Case("BUD-005", "saving", LedgerTestTags.BUDGET_ADJUSTMENT_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.SAVING), mapOf("type" to "SUBTRACT")),
            Case("BUD-006", "content", LedgerTestTags.BUDGET_HISTORY, BudgetDeviceFixtures.state(current, BudgetPresentation.CONTENT)),
            Case(
                "BUD-006",
                "singleRevision",
                LedgerTestTags.BUDGET_HISTORY,
                BudgetDeviceFixtures.state(
                    BudgetDeviceFixtures.configured(BudgetDeviceFixtures.Configuration(historyCount = 1)),
                    BudgetPresentation.SINGLE_REVISION,
                ),
            ),
            Case("BUD-007", "content", LedgerTestTags.BUDGET_TEMPLATES, BudgetDeviceFixtures.state(current, BudgetPresentation.CONTENT)),
            Case(
                "BUD-007",
                "empty",
                LedgerTestTags.BUDGET_TEMPLATES,
                BudgetDeviceFixtures.state(
                    BudgetDeviceFixtures.configured(
                        BudgetDeviceFixtures.Configuration(withTemplates = false),
                    ),
                    BudgetPresentation.EMPTY,
                ),
            ),
            Case("BUD-008", "create", LedgerTestTags.BUDGET_TEMPLATE_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.CREATE)),
            Case("BUD-008", "edit", LedgerTestTags.BUDGET_TEMPLATE_EDITOR, BudgetDeviceFixtures.state(current, BudgetPresentation.EDIT)),
            Case("BUD-008", "constraintError", LedgerTestTags.BUDGET_TEMPLATE_EDITOR, BudgetDeviceFixtures.constraintState()),
        )
        return raw.mapIndexed { index, case -> case.copy(width = listOf(320, 360, 480)[index % 3], fontScale = listOf(1f, 1.3f, 2f)[index % 3], locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3], theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK) }
    }

    private data class Case(
        val screen: String,
        val stateName: String,
        val expectedTag: String,
        val featureState: BudgetFeatureState,
        val arguments: Map<String, String> = emptyMap(),
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    private companion object {
        val EXPECTED = linkedMapOf(
            "BUD-001" to setOf("configured", "notConfigured", "recalculating", "historical", "future"),
            "BUD-002" to setOf("editing", "constraintError", "saving", "historyRecalculationWarning"),
            "BUD-003" to setOf("editing", "constraintError"),
            "BUD-004" to setOf("content", "empty"),
            "BUD-005" to setOf("editing", "invalid", "saving"),
            "BUD-006" to setOf("content", "singleRevision"),
            "BUD-007" to setOf("content", "empty"),
            "BUD-008" to setOf("create", "edit", "constraintError"),
        )
    }
}
