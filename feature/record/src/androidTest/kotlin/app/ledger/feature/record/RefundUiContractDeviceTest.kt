@file:Suppress("LongMethod", "MaxLineLength")

package app.ledger.feature.record

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.application.RefundSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RefundUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rec015AndRec016RenderEveryRequiredStateAcrossResponsiveAccessibleLocalizedMatrix() {
        val cases = cases()
        assertEquals(8, cases.size)
        assertEquals(
            mapOf(
                "REC-015" to setOf("linked", "independent", "partiallyRefunded", "exceedsRemaining", "saving"),
                "REC-016" to setOf("content", "empty", "searching"),
            ),
            cases.groupBy(Case::screen).mapValues { it.value.map(Case::stateName).toSet() },
        )
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val current = active.value
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val locale = Locale.forLanguageTag(current.locale)
            val localized = base.createConfigurationContext(Configuration(configuration).apply { setLocales(LocaleList(locale)) })
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalDensity provides Density(1f, current.fontScale)) {
                LedgerTheme(current.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(current.width.dp, 1_700.dp)) {
                        if (current.screen == "REC-015") {
                            LedgerScaffold(fixedAction = { LedgerSaveFab({}, submitting = current.editor?.presentation == RefundPresentation.SAVING) }) { padding ->
                                RefundDestination(RefundLoadState.Content(requireNotNull(current.editor)), RefundDeviceFixtures.actions, Modifier.padding(padding))
                            }
                        } else {
                            RefundOriginalPickerDestination(requireNotNull(current.picker), RefundDeviceFixtures.pickerActions)
                        }
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            if (case.screen == "REC-015") {
                composeRule.onNodeWithTag(LedgerTestTags.REFUND_ROOT).assertExists()
                composeRule.onNodeWithTag(LedgerTestTags.SAVE).assertExists().assertHasClickAction()
            } else {
                composeRule.onNodeWithTag(LedgerTestTags.REFUND_PICKER).assertExists()
            }
        }
    }

    @Test
    fun highRiskOverrideAndThreeDateDimensionsRemainReachableAtTwoHundredPercentFont() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_200.dp)) {
                        RefundDestination(RefundLoadState.Content(RefundDeviceFixtures.excess()), RefundDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.REFUND_FORM).performScrollToNode(hasTestTag(LedgerTestTags.REFUND_TIME_DIMENSIONS))
        composeRule.onNodeWithTag(LedgerTestTags.REFUND_TIME_DIMENSIONS).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.REFUND_FORM).performScrollToNode(hasTestTag(LedgerTestTags.REFUND_EXCESS_CONFIRMATION))
        composeRule.onNodeWithTag(LedgerTestTags.REFUND_EXCESS_CONFIRMATION).assertExists()
    }

    private fun cases(): List<Case> {
        val linked = RefundDeviceFixtures.linked()
        val raw = listOf(
            Case("REC-015", "linked", editor = linked),
            Case("REC-015", "independent", editor = RefundDeviceFixtures.independent()),
            Case("REC-015", "partiallyRefunded", editor = linked),
            Case("REC-015", "exceedsRemaining", editor = RefundDeviceFixtures.excess()),
            Case("REC-015", "saving", editor = linked.copy(presentation = RefundPresentation.SAVING)),
            Case("REC-016", "content", picker = RefundPickerState.Content(RefundDeviceFixtures.snapshot())),
            Case("REC-016", "empty", picker = RefundPickerState.Content(RefundDeviceFixtures.snapshot(empty = true))),
            Case("REC-016", "searching", picker = RefundPickerState.Content(RefundDeviceFixtures.snapshot(), RefundSearchQuery("Lunch"), searching = true)),
        )
        return raw.mapIndexed { index, case -> case.copy(width = listOf(320, 360, 480)[index % 3], fontScale = listOf(1f, 1.3f, 2f)[index % 3], locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3], theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK) }
    }

    private data class Case(
        val screen: String,
        val stateName: String,
        val editor: RefundEditorState? = null,
        val picker: RefundPickerState? = null,
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )
}
