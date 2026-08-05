@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.feature.settlement

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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettlementUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allTwentyThreeSetRequiredStatesRenderAcrossWidthFontLocaleAndThemeMatrix() {
        val cases = cases()
        assertEquals(23, cases.size)
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
                        SettlementDestination(case.screen, SettlementLoadState.Content(case.state), SettlementDeviceFixtures.actions)
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
    fun paymentAccountRequirementAndExternalOnlyPathRemainExplicitAtCompactTwoHundredPercentFont() {
        val active = mutableStateOf(SettlementDeviceFixtures.state("SET-006", SettlementPresentation.SELF_PAYS))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_800.dp)) {
                        SettlementDestination("SET-006", SettlementLoadState.Content(active.value), SettlementDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.SETTLEMENT_PAYMENT).assertExists()
        composeRule.runOnIdle { active.value = SettlementDeviceFixtures.state("SET-006", SettlementPresentation.EXTERNAL_TO_EXTERNAL) }
        composeRule.onNodeWithTag(LedgerTestTags.SETTLEMENT_PAYMENT).assertExists()
    }

    private fun cases(): List<Case> = EXPECTED.flatMap { (screen, states) ->
        states.map { stateName -> Case(screen, stateName, TAGS.getValue(screen), SettlementDeviceFixtures.state(screen, PRESENTATIONS.getValue(stateName))) }
    }.mapIndexed { index, case ->
        case.copy(
            width = listOf(320, 360, 480)[index % 3],
            fontScale = listOf(1f, 1.3f, 2f)[index % 3],
            locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3],
            theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
        )
    }

    private data class Case(
        val screen: String,
        val stateName: String,
        val tag: String,
        val state: SettlementFeatureState,
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    companion object {
        val EXPECTED = linkedMapOf(
            "SET-001" to setOf("content", "empty", "requiresAdditionalSettlement"),
            "SET-002" to setOf("create", "edit", "validationError"),
            "SET-003" to setOf("content", "empty"),
            "SET-004" to setOf("open", "settled", "requiresAdditionalSettlement", "empty"),
            "SET-005" to setOf("receivable", "payable", "zero"),
            "SET-006" to setOf("selfPays", "selfReceives", "externalToExternal", "saving"),
            "SET-007" to setOf("content", "empty"),
            "SET-008" to setOf("required", "resolved"),
        )
        val PRESENTATIONS = mapOf(
            "content" to SettlementPresentation.CONTENT,
            "empty" to SettlementPresentation.EMPTY,
            "requiresAdditionalSettlement" to SettlementPresentation.REQUIRES_ADDITIONAL_SETTLEMENT,
            "create" to SettlementPresentation.CREATE,
            "edit" to SettlementPresentation.EDIT,
            "validationError" to SettlementPresentation.VALIDATION_ERROR,
            "open" to SettlementPresentation.OPEN,
            "settled" to SettlementPresentation.SETTLED,
            "receivable" to SettlementPresentation.RECEIVABLE,
            "payable" to SettlementPresentation.PAYABLE,
            "zero" to SettlementPresentation.ZERO,
            "selfPays" to SettlementPresentation.SELF_PAYS,
            "selfReceives" to SettlementPresentation.SELF_RECEIVES,
            "externalToExternal" to SettlementPresentation.EXTERNAL_TO_EXTERNAL,
            "saving" to SettlementPresentation.SAVING,
            "required" to SettlementPresentation.REQUIRED,
            "resolved" to SettlementPresentation.RESOLVED,
        )
        val TAGS = mapOf(
            "SET-001" to LedgerTestTags.SETTLEMENT_HOME,
            "SET-002" to LedgerTestTags.SETTLEMENT_EDITOR,
            "SET-003" to LedgerTestTags.SETTLEMENT_PARTICIPANTS,
            "SET-004" to LedgerTestTags.SETTLEMENT_DETAIL,
            "SET-005" to LedgerTestTags.SETTLEMENT_POSITIONS,
            "SET-006" to LedgerTestTags.SETTLEMENT_PAYMENT,
            "SET-007" to LedgerTestTags.SETTLEMENT_HISTORY,
            "SET-008" to LedgerTestTags.SETTLEMENT_ADDITIONAL,
        )
    }
}
